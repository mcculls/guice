/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject.internal;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.inject.spi.InjectionPoint;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.aopalliance.intercept.MethodInterceptor;

/**
 * Builds a construction proxy that can participate in AOP. This class manages applying type and
 * method matchers to come up with the set of intercepted methods.
 *
 * @author jessewilson@google.com (Jesse Wilson)
 */
final class ProxyFactory<T> implements ConstructionProxyFactory<T> {

  private static final Logger logger = Logger.getLogger(ProxyFactory.class.getName());

  private final InjectionPoint injectionPoint;
  private final ImmutableMap<Method, List<MethodInterceptor>> interceptors;
  private final Class<?> declaringClass;
  private final InvocationHandler[] callbacks;

  private Enhancer enhancer;

  ProxyFactory(InjectionPoint injectionPoint, Iterable<MethodAspect> methodAspects) {
    this.injectionPoint = injectionPoint;

    declaringClass = injectionPoint.getMember().getDeclaringClass();

    // Find applicable aspects. Bow out if none are applicable to this class.
    List<MethodAspect> applicableAspects = Lists.newArrayList();
    for (MethodAspect methodAspect : methodAspects) {
      if (methodAspect.matches(declaringClass)) {
        applicableAspects.add(methodAspect);
      }
    }

    if (applicableAspects.isEmpty()) {
      interceptors = ImmutableMap.of();
      callbacks = null;
      enhancer = null;
      return;
    }

    enhancer = BytecodeGen.newEnhancerForClass(declaringClass);
    Method[] methods = enhancer.getEnhanceableMethods();

    MethodInterceptorsPair[] methodInterceptorsPairs = null; // lazy

    // Iterate over aspects and add interceptors for the methods they apply to
    for (MethodAspect methodAspect : applicableAspects) {
      for (int methodIndex = 0; methodIndex < methods.length; methodIndex++) {
        Method method = methods[methodIndex];
        if (methodAspect.matches(method)) {
          if (method.isSynthetic()) {
            logger.log(
                Level.WARNING,
                "Method [{0}] is synthetic and is being intercepted by {1}."
                    + " This could indicate a bug.  The method may be intercepted twice,"
                    + " or may not be intercepted at all.",
                new Object[] {method, methodAspect.interceptors()});
          }

          if (methodInterceptorsPairs == null) {
            methodInterceptorsPairs = new MethodInterceptorsPair[methods.length];
          }
          MethodInterceptorsPair pair = methodInterceptorsPairs[methodIndex];
          if (pair == null) {
            pair = new MethodInterceptorsPair(method);
            methodInterceptorsPairs[methodIndex] = pair;
          }
          pair.addAll(methodAspect.interceptors());
        }
      }
    }

    if (methodInterceptorsPairs == null) {
      interceptors = ImmutableMap.of();
      callbacks = null;
      enhancer = null;
      return;
    }

    ImmutableMap.Builder<Method, List<MethodInterceptor>> interceptorsMapBuilder =
        ImmutableMap.builder();

    callbacks = new InvocationHandler[methods.length];
    for (int methodIndex = 0; methodIndex < methods.length; methodIndex++) {
      MethodInterceptorsPair pair = methodInterceptorsPairs[methodIndex];
      if (pair == null) {
        continue;
      }

      List<MethodInterceptor> deDuplicated = pair.dedup();
      interceptorsMapBuilder.put(pair.method, deDuplicated);
      callbacks[methodIndex] =
          new InterceptorStackCallback(pair.method, deDuplicated, enhancer, methodIndex);
    }

    interceptors = interceptorsMapBuilder.build();
  }

  /** Returns the interceptors that apply to the constructed type. */
  public ImmutableMap<Method, List<MethodInterceptor>> getInterceptors() {
    return interceptors;
  }

  @Override
  public ConstructionProxy<T> create() throws ErrorsException {
    if (interceptors.isEmpty()) {
      return new DefaultConstructionProxyFactory<T>(injectionPoint).create();
    }

    // Create the proxied class. We're careful to ensure that interceptor state is not-specific
    // to this injector. Otherwise, the proxies for each injector will waste PermGen memory
    try {
      return new ProxyConstructor<T>(enhancer, injectionPoint, callbacks, interceptors);
    } catch (Throwable e) {
      throw new Errors().errorEnhancingClass(declaringClass, e).toException();
    }
  }

  private static class MethodInterceptorsPair {
    final Method method;
    final ImmutableSet.Builder<MethodInterceptor> interceptorsSetBuilder;

    MethodInterceptorsPair(Method method) {
      this.method = method;
      this.interceptorsSetBuilder = ImmutableSet.builder();
    }

    void addAll(List<MethodInterceptor> interceptors) {
      this.interceptorsSetBuilder.addAll(interceptors);
    }

    List<MethodInterceptor> dedup() {
      return interceptorsSetBuilder.build().asList();
    }
  }

  /** Constructs instances that participate in AOP. */
  private static class ProxyConstructor<T> implements ConstructionProxy<T> {
    final Enhancer enhancer;
    final InjectionPoint injectionPoint;
    final Constructor<T> constructor;
    final int constructorIndex;
    final InvocationHandler[] callbacks;
    final ImmutableMap<Method, List<MethodInterceptor>> methodInterceptors;

    @SuppressWarnings("unchecked") // the constructor promises to construct 'T's
    ProxyConstructor(
        Enhancer enhancer,
        InjectionPoint injectionPoint,
        InvocationHandler[] callbacks,
        ImmutableMap<Method, List<MethodInterceptor>> methodInterceptors) {
      this.enhancer = enhancer;
      this.injectionPoint = injectionPoint;
      this.constructor = (Constructor<T>) injectionPoint.getMember();
      this.constructorIndex = enhancer.getConstructorIndex(constructor.getParameterTypes());
      this.callbacks = callbacks;
      this.methodInterceptors = methodInterceptors;
    }

    @Override
    @SuppressWarnings("unchecked") // the enhancer promises to produce 'T's
    public T newInstance(Object... arguments) throws InvocationTargetException {
      return (T) enhancer.newEnhancedInstance(constructorIndex, arguments, callbacks);
    }

    @Override
    public InjectionPoint getInjectionPoint() {
      return injectionPoint;
    }

    @Override
    public Constructor<T> getConstructor() {
      return constructor;
    }

    @Override
    public ImmutableMap<Method, List<MethodInterceptor>> getMethodInterceptors() {
      return methodInterceptors;
    }
  }
}
