package com.google.inject;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.google.common.testing.NullPointerTester;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matchers;
import com.google.inject.testing.NullpointerObject;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.function.Function;
import junit.framework.TestCase;
import org.junit.Test;

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

  static class ConstructorThrowsException {
    @Inject  static ConstructorThrowsException instance;

    @Inject
    ConstructorThrowsException() throws Exception {
      throw  new ExecutionError("failed", new IllegalAccessError("failed"));
    }
  }

  @Test
  public void testStaticInjection() {
    try {
      Guice.createInjector(new AbstractModule() {
        @Override
        protected void configure() {
          requestStaticInjection(ConstructorThrowsException.class);
        }
      });
      fail("expected to fail");
    } catch (CreationException e) {
      // expected
    }
  }


  static class Foo {
    @Inject
    Foo() {
      throw new AssertionError("fail!");
    }
  }

  @Test
  public void testAssertionErrorInConstructor() {
    Injector injector =    Guice.createInjector();
    try {
      injector.getInstance(Foo.class);
      fail("expected to throw exception");
    } catch (RuntimeException e) {
      // expected.
    }
  }

  @Test
  public void testEnhancedMethodVisibility() {
    NullpointerObject object = Guice.createInjector(
        new AbstractModule() {
          @Override
          protected void configure() {
            bindInterceptor(Matchers.any(), Matchers.annotatedWith(NullpointerObject.Intercept.class),
                invocation -> invocation.proceed());
          }
        }
    ).getInstance(NullpointerObject.class);
    new NullPointerTester().testAllPublicInstanceMethods(object);
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Inherited
  @interface Measure {}

  static abstract class Base<T> {
    protected final T t;

    protected Base(T t) {
      this.t = t;
    }

    protected  T getT() {return t;}
  }

  static abstract class Subclass<F> extends Base<String> {
    protected Subclass(String t) {
      super(t);
    }

    protected abstract void otherMethod(F f);

    @Override
    protected final String getT() {return t;}
  }

  @Measure
  static class SubSubClass extends Subclass<String> {
    @Inject
    SubSubClass() {
      super("");
    }

    @Override
    protected final void otherMethod(String s) {}
  }

  @Test
  public void testOverrideFinalMethod() {
    Class<?> c = Guice.createInjector(new AbstractModule() {
      @Override
      protected void configure() {
        bindInterceptor(Matchers.annotatedWith(Measure.class), Matchers.any(), invocation -> invocation.proceed());
      }
    }).getInstance(SubSubClass.class)
    .getClass();
    assertNotNull(c.getCanonicalName());
  }
}
