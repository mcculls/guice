/*
 * Copyright (C) 2019 Google Inc.
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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Supports enhancing a particular type with new behaviour.
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
interface Enhancer {

  /**
   * Gets all methods from the original type that can be enhanced by applying invocation handlers.
   */
  Method[] getEnhanceableMethods();

  /**
   * Gets the index of the constructor with the declared parameter types.
   */
  int getConstructorIndex(Class<?>[] parameterTypes);

  /**
   * Creates a new enhanced instance of the original type.
   *
   * @param constructorIndex the index retrieved by {@link #getConstructorIndex(Class[])}
   * @param handlers the handlers to map 1-1 to methods returned by {@link #getEnhanceableMethods()}
   */
  Object newEnhancedInstance(int constructorIndex, Object[] args, InvocationHandler[] handlers)
      throws InvocationTargetException;

  /**
   * Invokes the original (unenhanced) method of an enhanced instance.
   *
   * @param methodIndex the index into the methods returned by {@link #getEnhanceableMethods()}
   */
  Object invokeOriginal(int methodIndex, Object enhancedInstance, Object[] args)
      throws InvocationTargetException;
}
