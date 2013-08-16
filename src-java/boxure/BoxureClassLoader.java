package boxure;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Pattern;


public class BoxureClassLoader extends URLClassLoader {

  private final static String ISOLATE =
       "clojure\\.lang\\.RT.*"
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

    final Class<?> clazz = super.findLoadedClass(name);
    if (clazz != null) return clazz;

    if (name.matches(ISOLATE)) {
        System.out.println("[Boxure loading class "+ name +" in isolation]");
        return super.findClass(name);
    } else return super.loadClass(name, resolve);
  }
}
