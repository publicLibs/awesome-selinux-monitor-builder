/**
 *
 */
package com.publiclibs.awesome.selinux.monitorbuilder;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

/**
 * @author freedom1b2830
 * @date 2023-февраля-25 23:14:53
 */
public class MonitorBuilder {

	public static class WatchDir {
		@SuppressWarnings("unchecked")
		static <T> WatchEvent<T> cast(final WatchEvent<?> event) {
			return (WatchEvent<T>) event;
		}

		private final WatchService watcher;
		private final Map<WatchKey, Path> keys;
		private final boolean recursive;
		private final boolean trace;
		private final FileSystem fs;

		private final MonitorBuilderThread monitorBuilderThread;
		public boolean active = true;

		private WatchDir(final Path dir, final boolean recursiveIn) throws IOException {

			this.fs = FileSystems.getDefault();
			this.watcher = fs.newWatchService();
			this.keys = new HashMap<>();
			this.recursive = recursiveIn;
			monitorBuilderThread = new MonitorBuilderThread(this, dir);

			if (recursive) {
				System.out.format("Scanning %s ...\n", dir);
				registerAll(dir);
				System.out.println("Done.");
			} else {
				register(dir);
			}
			monitorBuilderThread.start();

			// enable trace after initial registration
			this.trace = true;
		}

		void processEvents() throws InterruptedException {
			for (;;) {

				// wait for key to be signalled
				WatchKey key;
				try {
					key = watcher.take();
				} catch (final InterruptedException x) {
					return;
				}

				final Path dir = keys.get(key);
				if (dir == null) {
					System.err.println("WatchKey not recognized!!");
					continue;
				}

				for (final WatchEvent<?> event : key.pollEvents()) {
					final Kind<?> kind = event.kind();

					// TBD - provide example of how OVERFLOW event is handled
					if (kind == OVERFLOW) {
						continue;
					}

					// Context for directory entry event is the file name of entry
					final WatchEvent<Path> ev = cast(event);
					final Path name = ev.context();
					final Path child = dir.resolve(name);

					final Kind<?> eventKind = event.kind();
					final String eventKindName = eventKind.name();
					// print out event
					System.out.format("%s: %s%n", eventKindName, child);

					// if directory is created, and watching recursively, then
					// register it and its sub-directories
					if (recursive && (kind == ENTRY_CREATE)) {
						try {
							if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
								registerAll(child);
							}

						} catch (final IOException x) {
							// ignore to keep sample readbale
						}
					}

					if (kind == ENTRY_MODIFY || kind == ENTRY_CREATE) {
						monitorBuilderThread.add2Queue(child);
					}
				}

				// reset key and remove from set if directory no longer accessible
				final boolean valid = key.reset();
				if (!valid) {
					keys.remove(key);

					// all directories are inaccessible
					if (keys.isEmpty()) {
						break;
					}
				}
			}
		}

		private void register(final Path dir) throws IOException {
			final WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
			if (trace) {
				final Path prev = keys.get(key);
				if (prev == null) {
					System.out.format("register: %s\n", dir);
				} else {
					if (!dir.equals(prev)) {
						System.out.format("update: %s -> %s\n", prev, dir);
					}
				}
			}
			keys.put(key, dir);
		}

		private void registerAll(final Path start) throws IOException {
			// register directory and sub-directories
			Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs)
						throws IOException {
					register(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}

	}

	/**
	 * @param args
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public static void main(final String[] args) throws IOException, InterruptedException {
		if (args.length != 1) {
			throw new IllegalArgumentException("1 arg in path to target_dir/.git");
		}
		final Path rootDir = Paths.get(args[0]);
		if (Files.notExists(rootDir)) {
			throw new FileNotFoundException(rootDir.toString() + " not found");
		}

		new WatchDir(rootDir, true).processEvents();
	}

}
