package boxure;

import clojure.lang.DynamicClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Pattern;


public class BoxureClassLoader extends URLClassLoader {


  // Reflection magic, needed to call findLoadedClass on parent.
  private static Method flcMethod = null;

  static {
    try {
      flcMethod = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
      flcMethod.setAccessible(true);
    } catch (final NoSuchMethodException nsme) {
      nsme.printStackTrace();
    }
  }


  private final static String ISOLATE =
       "clojure\\.lang\\.RT.*"                 // Uses Compiler and Namespace, which need isolation.
    + "|clojure\\.lang\\.Compiler.*"
    + "|clojure\\.lang\\.Namespace.*"          // Holds the defined vars in a static.
    + "|clojure\\.lang\\.Var.*"                // Uses Namespace, which needs isolation.
    + "|clojure\\.lang\\.Agent.*"              // We require a threadpool per Clojure instance.
    + "|clojure\\.lang\\.DynamicClassLoader.*" // We require a class cache per Clojure instance.
    + "|clojure\\.lang\\.LispReader.*"         // Uses Compiler in SyntaxQuoteReader.syntaxQuote(..)

    // All Clojure functions that are loaded by Boxure need to be redefined,
    // such that they use the isolated classes.
    + "|clojure\\.core.*"
    + "|clojure\\.main.*"
    + "|clojure\\.genclass.*"
    + "|clojure\\.gvec.*"
    + "|clojure\\.instant.*"
    + "|clojure\\.uuid.*"
    + "|clojure\\.java.*"
    + "|clojure\\.string.*"
    + "|clojure\\.edn.*"
    + "|clojure\\.walk.*"
    + "|clojure\\.set.*"
    + "|clojure\\.tools.*"
    + "|clojure\\.data.*"
    + "|boxure\\.core.*"
    + "|leiningen\\.core.*"
    + "|cemerick\\.pomegranate.*"
    + "|dynapath.*"
    + "|useful.*"
    + "|clojure\\.tools.*"
    + "|classlojure\\.core.*"
    + "|compile__stub.*"
    ;


  private ClassLoader parent = null;
  private String userIsolate = null;
  private boolean logging = false;

  public BoxureClassLoader(final URL[] urls, final ClassLoader parent, final String userIsolate,
                           final boolean logging) {
    super(urls, parent);
    this.parent = parent;
    this.userIsolate = userIsolate;
    this.logging = logging;
  }


  /**
   * Process:
   * 1. Check if it is already loaded by this classloader, if so return it.
   * 2. Check if the class needs to be loaded in isolation for sure. If so, try
   *    to load the class here, or fail.
   * 3. Check if the parent classloader (the standard Application ClassLoader)
   *    has already loaded it. If so, use that one.
   * 4. Try to find the class in here. If it succeeds, use that one.
   * 5. Try to load the class in the normal way.
   */
  protected Class<?> loadClass(final String name, final boolean resolve)
    throws ClassNotFoundException {

    final Class<?> clazz = super.findLoadedClass(name);
    if (clazz != null) return clazz;

    if (name.matches(ISOLATE) || name.matches(userIsolate)) {
      try {
        final Class<?> isoClazz = super.findClass(name);
        log("[Boxure loading class "+ name +" in isolation]");
        return isoClazz;
      } catch (ClassNotFoundException cnfe) {
        log("[Boxure could not load class "+ name +" in isolation]");
        throw cnfe;
      }
    } else {
      final Class<?> parentClazz = findLoadedClassParent(name);
      if (parentClazz != null) {
        log("[Boxure uses already loaded class in parent "+ name +"]");
        return parentClazz;
      } else try {
          final Class<?> loadedOurself = super.findClass(name);
          log("[Boxure loads class "+ name +" itself, as direct parent had not loaded it]");
          return loadedOurself;
        } catch (ClassNotFoundException cnfe) {
          try {
            final Class<?> superClazz = super.loadClass(name, resolve);
            log("[Boxure could not find class "+ name +
                "itself, thus loaded it by normal classloading.]");
            return superClazz;
          } catch (ClassNotFoundException cnfe2) {
            log("[Boxure could not find class "+ name +" itself, nor by normal classloading]");
            throw cnfe2;
          }
        }
    }
  }


  private Class<?> findLoadedClassParent(final String name) {
    try {
      final Object result = flcMethod.invoke(parent, name);
      if (result != null) return (Class<?>) result;
    } catch (final IllegalAccessException iae) {
      iae.printStackTrace();
    } catch (final InvocationTargetException ite) {
      ite.printStackTrace();
    }
    return null;
  }


  private void log(final String msg) {
    if (logging) {
      System.out.println(msg);
    }
  }

}
