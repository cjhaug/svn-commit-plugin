package jenkins.plugins.svncommit;

import hudson.Util;
import hudson.PluginWrapper;

import java.io.IOException;

import org.jvnet.hudson.test.TestPluginManager;

/**
 * Cleanup the temporary directory created by
 * org.jvnet.hudson.test.TestPluginManager. Needed for Jenkins < 1.510
 * 
 * Call TestPluginManagerCleanup.registerCleanup() at least once from anywhere.
 */
public class TestPluginManagerCleanup {
	private static Thread deleteThread = null;

	public static synchronized void registerCleanup() {
		if (deleteThread != null) {
			return;
		}
		deleteThread = new Thread("HOTFIX: cleanup "
				+ TestPluginManager.INSTANCE.rootDir) {
			@Override
			public void run() {
				if (TestPluginManager.INSTANCE != null
						&& TestPluginManager.INSTANCE.rootDir != null
						&& TestPluginManager.INSTANCE.rootDir.exists()) {
					// Work as PluginManager#stop
					for (PluginWrapper p : TestPluginManager.INSTANCE
							.getPlugins()) {
						p.stop();
						p.releaseClassLoader();
					}
					TestPluginManager.INSTANCE.getPlugins().clear();
					System.gc();
					try {
						Util.deleteRecursive(TestPluginManager.INSTANCE.rootDir);
					} catch (IOException x) {
						x.printStackTrace();
					}
				}
			}
		};
		Runtime.getRuntime().addShutdownHook(deleteThread);
	}
}
