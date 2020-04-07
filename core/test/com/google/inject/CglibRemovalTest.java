package com.google.inject;

import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matchers;
import junit.framework.TestCase;
import org.aopalliance.intercept.Invocation;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

public class CglibRemovalTest extends TestCase {
  interface Interface {
    int method();
  }

  static class Impl implements Interface {
    private final String str;

    @Inject
    Impl(String str) {
      this.str = str;
    }

    @Override
    public int method() {
      return str.hashCode();
    }
  }

  private static final String STRING = "foobar";

  static class TestModule extends AbstractModule {
    @Override
    protected void configure() {
      bind(String.class).toInstance(STRING);
      bind(Interface.class).to(Impl.class);
    }
  }

  static class InterceptorModule extends AbstractModule {
    @Override
    protected void configure() {
      // Test passes if no interceptor is bound.
      bindInterceptor(Matchers.any(), Matchers.returns(Matchers.subclassesOf(int.class)),
          invocation -> invocation.proceed());
    }
  }

  @Test
  public void testUseInjectedClass() throws Throwable {
    Interface instance = Guice.createInjector(new TestModule(), new InterceptorModule()).getInstance(Interface.class);
    Class<? extends Interface> klass = instance.getClass();
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    CallSite callsite = LambdaMetafactory.metafactory(
        lookup,
        "apply",
        MethodType.methodType(Function.class),
        MethodType.methodType(Object.class, Object.class),
        lookup.findVirtual(klass, "method", MethodType.methodType(int.class)),
        MethodType.methodType(int.class, klass)
    );
    Object res = ((Function)callsite.getTarget().invokeExact()).apply(instance);
    assertEquals(STRING.hashCode(), res);
  }

  @Test
  public void testMockitoSpy() throws Exception {
    Interface instance = spy(Guice.createInjector(
        new TestModule(),
        new InterceptorModule()
    ).getInstance(Interface.class));

    instance.method();
    verify(instance).method();
  }

  @Test
  public void testMethodMatcherError() throws Exception {
    Module interceptorModule = new AbstractModule() {
      @Override
      protected void configure() {
        bindInterceptor(Matchers.subclassesOf(Interface.class), new AbstractMatcher<Method>() {
              @Override
              public boolean matches(Method method) {
                if (method.getReturnType().isPrimitive()) {
                  throw new IllegalArgumentException("can't intercept method " + method.getName());
                }
                return true;
              }
            },
            invocation -> invocation.proceed());
      }
    };
    try {
      Guice.createInjector(new TestModule(), interceptorModule);
      fail("should fail");
    } catch (UncheckedExecutionException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
    }
  }

  private static class PrivateConstructor {
    int method() {
      return 1;
    }
  }

  @Test
  public void testPrivateConstructor() {
    Injector injector = Guice.createInjector(new InterceptorModule());
    try {
      injector.getInstance(PrivateConstructor.class);
      fail("should fail");
    } catch (ConfigurationException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
    }
  }
}
