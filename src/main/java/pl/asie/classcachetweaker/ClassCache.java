/**
 * This file is part of ClassCacheTweaker.
 *
 * ClassCacheTweaker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ClassCacheTweaker is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ClassCacheTweaker.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Additional permission under GNU GPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or
 * combining it with the Minecraft game engine, the Mojang Launchwrapper,
 * the Mojang AuthLib and the Minecraft Realms library (and/or modified
 * versions of said software), containing parts covered by the terms of
 * their respective licenses, the licensors of this Program grant you
 * additional permission to convey the resulting work.
 */
package pl.asie.classcachetweaker;

import com.google.common.collect.Sets;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.lwjgl.Sys;

import javax.swing.*;
import java.awt.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;

public class ClassCache implements Serializable {
	public static final int CURRENT_VERSION = 3;

	private transient static final Field PACKAGES;
	private transient static final Field CACHED_CLASSES;
	private transient static final Field MANIFESTS;
	private transient static final Field SEAL_BASE;
	public transient static final MethodHandle DEFINE_CLASS;
	private transient static final MethodHandle DEFINE_PACKAGE;

	static {
		Field PACKAGES_TMP = null;

		try {
			PACKAGES_TMP = ClassLoader.class.getDeclaredField("packages");
		} catch (Exception e) {
			e.printStackTrace();
		}

		PACKAGES = PACKAGES_TMP;
		PACKAGES.setAccessible(true);

		Field CACHED_CLASSES_TMP = null;

		try {
			CACHED_CLASSES_TMP = LaunchClassLoader.class.getDeclaredField("cachedClasses");
		} catch (Exception e) {
			e.printStackTrace();
		}

		CACHED_CLASSES = CACHED_CLASSES_TMP;
		CACHED_CLASSES.setAccessible(true);

		Field MANIFESTS_TMP = null;

		try {
			MANIFESTS_TMP = LaunchClassLoader.class.getDeclaredField("packageManifests");
		} catch (Exception e) {
			e.printStackTrace();
		}

		MANIFESTS = MANIFESTS_TMP;
		MANIFESTS.setAccessible(true);

		Field SEAL_BASE_TMP = null;

		try {
			SEAL_BASE_TMP = Package.class.getDeclaredField("sealBase");
		} catch (Exception e) {
			e.printStackTrace();
		}

		SEAL_BASE = SEAL_BASE_TMP;
		SEAL_BASE.setAccessible(true);

		MethodHandle DEFINE_PACKAGE_TMP = null;

		try {
			Method m = ClassLoader.class.getDeclaredMethod("definePackage", String.class, String.class, String.class, String.class, String.class, String.class, String.class, URL.class);
			m.setAccessible(true);
			DEFINE_PACKAGE_TMP = MethodHandles.lookup().unreflect(m);
		} catch (Exception e) {
			e.printStackTrace();
		}

		DEFINE_PACKAGE = DEFINE_PACKAGE_TMP;

		MethodHandle DEFINE_CLASS_TMP = null;

		try {
			Method m = SecureClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, CodeSource.class);
			m.setAccessible(true);
			DEFINE_CLASS_TMP = MethodHandles.lookup().unreflect(m);
		} catch (Exception e) {
			e.printStackTrace();
		}

		DEFINE_CLASS = DEFINE_CLASS_TMP;
	}

	private transient File classCacheFile;
	private transient File classCacheFileTmp;
	private transient File gameDir;
	protected transient LaunchClassLoader classLoader;
	private transient Thread saveThread;
	private transient Runnable saveRunnable;
	private transient boolean dirty;

	private Map<String, byte[]> classMap = new HashMap<>();
	protected Map<String, CodeSource> codeSourceMap = new HashMap<>();

	public static ClassCache load(LaunchClassLoader classLoader, File gameDir) throws IOException, IllegalAccessException, ClassNotFoundException {
		File classCacheFile = new File(gameDir, "classCache.dat");
		ClassCache cache = new ClassCache();

		cache.classCacheFile = classCacheFile;
		cache.classCacheFileTmp = new File(classCacheFile.getAbsolutePath() + "_tmp");
		cache.classLoader = classLoader;
		cache.gameDir = gameDir;

		if (cache.classCacheFileTmp.exists()) {
			cache.classCacheFileTmp.delete();
		}

		Map<String, Class<?>> cachedClassesOld = (Map<String, Class<?>>) CACHED_CLASSES.get(classLoader);
		if (!(cachedClassesOld instanceof ClassCacheConcurrentHashMap)) {
			Map<String, Class<?>> cachedClasses = new ClassCacheConcurrentHashMap(cache, classLoader);
			cachedClasses.putAll(cachedClassesOld);
			CACHED_CLASSES.set(classLoader, cachedClasses);
		}

		boolean loaded = false;
		if (classCacheFile.exists()) {

			try {
				FileInputStream fileInputStream = new FileInputStream(classCacheFile);
				DataInputStream dataInputStream = new DataInputStream(fileInputStream);
				ObjectInputStream objectInputStream = new ObjectInputStream(dataInputStream);
				int version = dataInputStream.readInt();
				if (version != CURRENT_VERSION) {
					throw new IOException("Invalid ClassCache.dat version!");
				}

				byte[] stateData = new byte[dataInputStream.readUnsignedShort()];
				dataInputStream.read(stateData);
				if (!Arrays.equals(stateData, ClassCacheState.generate(gameDir))) {
					throw new IOException("THIS IS NOT AN ERROR - Cache state changed, regenerating...");
				}

				int packageCount = dataInputStream.readInt();
				for (int i = 0; i < packageCount; i++) {
					String name = readNullableUTF(dataInputStream);
					String implTitle = readNullableUTF(dataInputStream);
					String implVendor = readNullableUTF(dataInputStream);
					String implVersion = readNullableUTF(dataInputStream);
					String specTitle = readNullableUTF(dataInputStream);
					String specVendor = readNullableUTF(dataInputStream);
					String specVersion = readNullableUTF(dataInputStream);
					URL url = dataInputStream.readBoolean() ? new URL(dataInputStream.readUTF()) : null;
					try {
						Package out = (Package) DEFINE_PACKAGE.invokeExact((ClassLoader) classLoader, name, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, url);
					} catch (IllegalArgumentException e) {
						// this means we already have that Package
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

				int classCount = dataInputStream.readInt();
				for (int i = 0; i < classCount; i++) {
					if (dataInputStream.readBoolean()) {
						String name = dataInputStream.readUTF();
						boolean hasCodeSource = dataInputStream.readBoolean();
						if (hasCodeSource) {
							CodeSource codeSource = (CodeSource) objectInputStream.readObject();
							cache.codeSourceMap.put(name, codeSource);
						}
						int dataLen = dataInputStream.readInt();
						byte[] data = new byte[dataLen];
						dataInputStream.read(data);
						cache.classMap.put(name, data);
					}
				}

				objectInputStream.close();
				dataInputStream.close();
				fileInputStream.close();

				loaded = true;
			} catch (Throwable t) {
				t.printStackTrace();
				cache = new ClassCache();

				cache.classCacheFile = classCacheFile;
				cache.classCacheFileTmp = new File(classCacheFile.getAbsolutePath() + "_tmp");
				cache.classLoader = classLoader;
				cache.gameDir = gameDir;
			}
		}

		if (!loaded && !((boolean) Launch.blackboard.get("fml.deobfuscatedEnvironment"))) {
			String ws = "Please note that ClassCacheTweaker is UNSUPPORTED and has a high chance to\n" +
					"** BREAK YOUR MODPACK IN STRANGE WAYS **!\n" +
					"Please only use it if you KNOW WHAT YOU ARE DOING!";
			System.out.println(ws);

			if (!GraphicsEnvironment.isHeadless()) {
				JOptionPane.showMessageDialog(new JFrame(), ws, "ClassCacheTweaker Warning", JOptionPane.WARNING_MESSAGE);
			}
		}

		final ClassCache cache1 = cache;
		cache.saveRunnable = new Runnable() {
			@Override
			public void run() {
				while (true) {
					if (cache1.dirty) {
						cache1.save();
						cache1.dirty = false;
					} else {
						break;
					}

					try {
						Thread.sleep(10000);
					} catch (InterruptedException e) {

					}
				}
			}
		};

		return cache;
	}

	public void add(String transformedName, byte[] data) {
		if (data == null) return;

		// System.out.println("Adding " + transformedName);
		synchronized (classMap) {
			classMap.put(transformedName, data);
		}

		dirty = true;
		if (saveThread == null || !saveThread.isAlive()) {
			saveThread = new Thread(saveRunnable);
			saveThread.start();
		}
	}

	private static void writeNullableUTF(DataOutputStream stream, String s) throws IOException {
		stream.writeBoolean(s != null);
		if (s != null) {
			stream.writeUTF(s);
		}
	}

	private static String readNullableUTF(DataInputStream stream) throws IOException {
		if (stream.readBoolean()) {
			return stream.readUTF();
		} else {
			return null;
		}
	}

	private void save() {
		try {
			FileOutputStream fileOutputStream = new FileOutputStream(classCacheFileTmp);
			DataOutputStream dataOutputStream = new DataOutputStream(fileOutputStream);
			ObjectOutputStream objectOutputStream = new ObjectOutputStream(dataOutputStream);

			dataOutputStream.writeInt(CURRENT_VERSION); // version

			byte[] stateData = ClassCacheState.generate(gameDir);
			dataOutputStream.writeShort(stateData.length);
			dataOutputStream.write(stateData);

			Map<String, Package> packageMap = (Map<String, Package>) PACKAGES.get(classLoader);
			Set<Package> packages = Sets.newHashSet(packageMap.values());

			dataOutputStream.writeInt(packages.size());
			for (Package pkg : packages) {
				writeNullableUTF(dataOutputStream, pkg.getName());
				writeNullableUTF(dataOutputStream, pkg.getImplementationTitle());
				writeNullableUTF(dataOutputStream, pkg.getImplementationVendor());
				writeNullableUTF(dataOutputStream, pkg.getImplementationVersion());
				writeNullableUTF(dataOutputStream, pkg.getSpecificationTitle());
				writeNullableUTF(dataOutputStream, pkg.getSpecificationVendor());
				writeNullableUTF(dataOutputStream, pkg.getSpecificationVersion());
				URL sealBase = (URL) SEAL_BASE.get(pkg);
				dataOutputStream.writeBoolean(sealBase != null);
				if (sealBase != null) {
					dataOutputStream.writeUTF(sealBase.toString());
				}
			}

			ClassCacheConcurrentHashMap cachedClasses = (ClassCacheConcurrentHashMap) CACHED_CLASSES.get(classLoader);

			synchronized (classMap) {
				dataOutputStream.writeInt(classMap.size());
				for (Map.Entry<String, byte[]> entry : classMap.entrySet()) {
					Class<?> cl = cachedClasses.getReal(entry.getKey());
					if (cl == null) {
						dataOutputStream.writeBoolean(false);
						continue;
					}

					CodeSource src = cl.getProtectionDomain().getCodeSource();

					boolean shouldWrite = true;
					if (src != null) {
						String loc = src.getLocation().toString();
						shouldWrite = !loc.startsWith("file:") || !loc.endsWith(".class");
					}

					dataOutputStream.writeBoolean(shouldWrite);
					if (shouldWrite) {
						dataOutputStream.writeUTF(entry.getKey());
						dataOutputStream.writeBoolean(src != null);
						if (src != null) {
							objectOutputStream.writeObject(src);
							objectOutputStream.flush();
						}
						dataOutputStream.writeInt(entry.getValue().length);
						dataOutputStream.write(entry.getValue());
					}
				}
			}

			objectOutputStream.close();
			dataOutputStream.flush();
			dataOutputStream.close();
			fileOutputStream.flush();
			fileOutputStream.close();

			classCacheFile.delete();
			classCacheFileTmp.renameTo(classCacheFile);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public byte[] get(Object transformedName) {
		return classMap.get(transformedName);
	}

    public void remove(final Object key) {
	    classMap.remove(key);
    }
}
