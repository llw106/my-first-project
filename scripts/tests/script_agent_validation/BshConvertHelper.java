package bsh.loader;

import java.io.IOException;

/** Parsing must never reach Android DEX conversion or load user-defined classes. */
public class BshConvertHelper {
    public ClassLoader convertClassToLoader(String name, byte[] bytes, ClassLoader parent) throws IOException {
        throw new AssertionError("Syntax validation attempted to load a generated class");
    }
    public ClassLoader convertDexToLoader(String path, ClassLoader parent) throws IOException {
        throw new AssertionError("Syntax validation attempted to load DEX");
    }
    public ClassLoader convertJarToLoader(String path, ClassLoader parent) throws IOException {
        throw new AssertionError("Syntax validation attempted to load JAR");
    }
    public ClassLoader convertAarToLoader(String path, ClassLoader parent) throws IOException {
        throw new AssertionError("Syntax validation attempted to load AAR");
    }
}
