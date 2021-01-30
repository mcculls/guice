package com.google.inject.testing;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import javax.inject.Inject;

public class NullpointerObject implements Action {
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Intercept {}

  static class TestObject {

    private final int i;

    TestObject(int i) {
      this.i = i;
    }
  }

  @Inject
  NullpointerObject() {
  }

  @Intercept
  void method(TestObject object) throws Exception {
    method(10, object);
  }

  @Intercept
  void otherMethod(TestObject o) throws IOException {
    throw new IOException("o");
  }

  private void method(int i, TestObject object) {
    System.out.println(object.i - i);
  }
}
