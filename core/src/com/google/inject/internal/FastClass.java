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

import java.lang.reflect.InvocationTargetException;

/**
 * Supports faster (pure-Java) reflection of a particular type.
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
interface FastClass {

  /**
   * Gets the index of the constructor with the declared parameter types.
   */
  int getConstructorIndex(Class<?>[] parameterTypes);

  /**
   * Gets the index of the named method with the declared parameter types.
   */
  int getMethodIndex(String name, Class<?>[] parameterTypes);

  /**
   * Creates a new instance of the original type.
   *
   * @param constructorIndex the index as returned by {@link #getConstructorIndex(Class[])}
   */
  Object newInstance(int constructorIndex, Object[] args) throws InvocationTargetException;

  /**
   * Invokes a method on an instance of the original type.
   *
   * @param methodIndex the index as returned by {@link #getMethodIndex(String, Class[])}
   */
  Object invoke(int methodIndex, Object instance, Object[] args) throws InvocationTargetException;
}
