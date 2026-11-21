package ink.hoka.odyssey.server;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * Загрузчик классов модуля. Порядок поиска класса: ядро (Minestom, API, JDK) → свой jar → jar модулей-зависимостей
 * (depends и softdepends, включая их зависимости). Модули, не указанные в зависимостях, друг другу не видны.
 */
final class ModuleClassLoader extends URLClassLoader {

    static { registerAsParallelCapable(); }

    private final String moduleId;
    private final List<ModuleClassLoader> deps;

    ModuleClassLoader(String moduleId, URL jar, ClassLoader parent, List<ModuleClassLoader> deps) {
        super("module:" + moduleId, new URL[]{jar}, parent);
        this.moduleId = moduleId;
        this.deps = deps;
    }

    String moduleId() { return moduleId; }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try { c = getParent().loadClass(name); } catch (ClassNotFoundException ignored) { }
            }
            if (c == null) c = findOwn(name);
            if (c == null) for (ModuleClassLoader d : deps) { c = d.findOwn(name); if (c != null) break; }
            if (c == null) throw new ClassNotFoundException(name + " (модуль " + moduleId + ")");
            if (resolve) resolveClass(c);
            return c;
        }
    }

    /** Класс только из своего jar (или null). */
    Class<?> findOwn(String name) {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;
            try { return findClass(name); } catch (ClassNotFoundException e) { return null; }
        }
    }
}
