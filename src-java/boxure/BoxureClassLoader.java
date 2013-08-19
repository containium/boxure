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


  private static boolean logging = false;


  private final static String ISOLATE =
       "clojure\\.lang\\.RT.*"                 // Uses Compiler and Namespace, which need isolation.
    + "|clojure\\.lang\\.Compiler.*"
    + "|clojure\\.lang\\.Namespace.*"          // Holds the defined vars in a static.
    + "|clojure\\.lang\\.Var.*"                // Uses Namespace, which needs isolation.
    + "|clojure\\.lang\\.Agent.*"              // We require a threadpool per Clojure instance.
    + "|clojure\\.lang\\.DynamicClassLoader.*" // We require a class cache per Clojure instance.

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

  public BoxureClassLoader(final URL[] urls, final ClassLoader parent) {
    super(urls, parent);
    this.parent = parent;
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

    if (name.matches(ISOLATE)) {
      log("[Boxure loading class "+ name +" in isolation]");
      return super.findClass(name);
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
          log("[Boxure tries normal class loading for "+ name +", as it cannot find it itself]");
          return super.loadClass(name, resolve);
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
