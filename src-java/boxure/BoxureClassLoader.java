package boxure;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Pattern;


public class BoxureClassLoader extends URLClassLoader {

  private final static String ISOLATE =
       "clojure.lang.RT.*"
    + "|clojure\\.lang\\.Compiler.*"
    + "|clojure\\.lang\\.Namespace.*"
    + "|clojure\\.lang\\.Symbol.*"
    + "|clojure\\.lang\\.ARef.*"
    + "|clojure\\.lang\\.Var.*"
    + "|clojure\\.lang\\.Keyword.*"
    + "|clojure\\.lang\\.KeywordLookupSite.*"
    + "|clojure\\.lang\\.MethodImplCache.*"
    + "|clojure\\.lang\\.AFunction.*"
    + "|clojure\\.lang\\.ArraySeq.*"
    + "|clojure\\.core.*"
    + "|clojure\\.main.*"
    + "|clojure\\.java.*";

  public BoxureClassLoader(final URL[] urls, final ClassLoader parent) {
    super(urls, parent);
  }

  protected Class<?> loadClass(final String name, final boolean resolve)
    throws ClassNotFoundException {

    Class<?> clazz = super.findLoadedClass(name);

    if (clazz == null)
      if (name.matches(ISOLATE)) {
        System.out.println("[Boxure loading class "+ name +"]");
        clazz = super.findClass(name);
      } else {
        clazz = super.loadClass(name, resolve);
        if (clazz != null && resolve)
          super.resolveClass(clazz);
        if (clazz == null)
          // If this is thrown, there is no Clojure runtime available in the classpath of the box.
          throw new ClassNotFoundException("Could load class "+ name +" in isolation.");
      }

    return clazz;
  }
}
