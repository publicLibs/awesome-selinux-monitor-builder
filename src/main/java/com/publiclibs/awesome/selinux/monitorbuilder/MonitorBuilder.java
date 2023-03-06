/**
 *
 */
package com.publiclibs.awesome.selinux.monitorbuilder;

import static java.nio.file.Files.isRegularFile;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

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

		private WatchDir(final Path dir, final boolean recursiveIn) throws IOException {
			this.fs = FileSystems.getDefault();
			this.watcher = fs.newWatchService();
			this.keys = new HashMap<>();
			this.recursive = recursiveIn;

			if (recursive) {
				System.out.format("Scanning %s ...\n", dir);
				registerAll(dir);
				System.out.println("Done.");
			} else {
				register(dir);
			}

			// enable trace after initial registration
			this.trace = true;
		}

		void processEvents() {
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
						if (isRegularFile(child)) {
							final String fileName = child.toFile().getName();
							if (fileName.contains(".")) {
								final String[] fileParts = fileName.split("\\.");
								final String ext = fileParts[fileParts.length - 1];
								if (EXTEN_LIST.contains(ext)) {
									final String moduleName = fileName.substring(0, fileName.length() - 3);
									final Path moduleDir = child.getParent();
									try {
										rebuildModule(moduleDir, moduleName);
									} catch (final Exception e) {
										e.printStackTrace();
									}
								}

							}

						}
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

	protected static final List<String> EXTEN_LIST = new ArrayList<>();
	static {
		EXTEN_LIST.add("te");
		EXTEN_LIST.add("fc");
		EXTEN_LIST.add("if");
	}

	/**
	 * Компилирует модуль
	 *
	 * @param makeFile   путь до MakeFile загруженной политики
	 * @param moduleDir  место где лежит модуль
	 * @param moduleName название модуля
	 * @return 0 если успешно
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private static int compile(final Path makeFile, final Path moduleDir, final String moduleName)
			throws IOException, InterruptedException {
		final String makeFileArg = makeFile.toAbsolutePath().toString();

		final ProcessBuilder makeBuilder = new ProcessBuilder("make", "-f", makeFileArg, moduleName);
		makeBuilder.directory(moduleDir.toFile());
		makeBuilder.redirectErrorStream(true);
		final Process buildProcess = makeBuilder.start();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(buildProcess.getInputStream(), StandardCharsets.UTF_8))) {
			reader.lines().filter(line -> !line.startsWith("m4:")).forEachOrdered(System.out::println);
		}
		return buildProcess.waitFor();
	}

	/**
	 * выполняет единствунную команду
	 *
	 * @param command единственная команда
	 * @return вывод команды
	 * @throws IOException
	 */
	private static List<String> getOutBySingleCMD(final String command) throws IOException {
		final ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.redirectErrorStream(true);
		final ArrayList<String> returnData = new ArrayList<>();
		final Process process = processBuilder.start();
		try (InputStream is = process.getInputStream()) {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
				reader.lines().forEachOrdered(returnData::add);
			}
		}
		return returnData;
	}

	/**
	 * возвращяет полный путь к MakeFile загруженной политики
	 *
	 * @return путь до MakeFile загруженной политики
	 * @throws IOException
	 */
	private static Path getPolicyCurrentMakeFile() throws IOException {
		final Path defaultInstallDir = Paths.get("/usr/share/selinux");
		final Path makeFile = defaultInstallDir.resolve(getPolicyName()).resolve("include/Makefile");
		if (Files.notExists(makeFile)) {
			throw new FileNotFoundException("cant get makefile in path" + makeFile);
		}
		return makeFile;
	}

	/**
	 * получение названия политики, вызвав sestatus
	 *
	 * @return название загруженной политики
	 * @throws IOException
	 */
	private static String getPolicyName() throws IOException {
		final List<String> data = getOutBySingleCMD("sestatus");
		for (final String string : data) {
			if (string.contains("Loaded policy name:")) {
				return string.split(":[ ]+")[1];
			}
		}
		throw new NoSuchElementException("cant get policy name by {sestatus}");
	}

	/**
	 * устанавливает модуль
	 *
	 * @param moduleDir  место где лежит модуль
	 * @param moduleName название модуля
	 * @return 0 если успешно
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private static int installModule(final Path moduleDir, final String moduleName)
			throws IOException, InterruptedException {
		final ProcessBuilder makeBuilder = new ProcessBuilder("semodule", "-i", moduleName);
		makeBuilder.directory(moduleDir.toFile());
		makeBuilder.redirectErrorStream(true);
		final Process buildProcess = makeBuilder.start();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(buildProcess.getInputStream(), StandardCharsets.UTF_8))) {
			reader.lines().filter(line -> !line.startsWith("m4:")).forEachOrdered(System.out::println);
		}
		// semodule -i $1.pp
		return buildProcess.waitFor();
	}

	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(final String[] args) throws IOException {
		if (args.length != 1) {
			throw new IllegalArgumentException("1 arg in path to policy/->>modules");
		}
		final Path rootDir = Paths.get(args[0]);
		if (Files.notExists(rootDir)) {
			throw new FileNotFoundException(rootDir.toString() + " not found");
		}
		new WatchDir(rootDir, true).processEvents();
		try (FileSystem fs = FileSystems.getDefault()) {
			try (WatchService watcher = fs.newWatchService()) {
			}

		}
	}

	/**
	 * собирает и устанавливает модуль
	 *
	 * @param moduleDir  место где лежит модуль
	 * @param moduleName название модуля
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public static void rebuildModule(final Path moduleDir, final String moduleName)
			throws IOException, InterruptedException {
		final Path makeFile = getPolicyCurrentMakeFile();

		final String name = moduleName + ".pp";
		final int statusCompile = compile(makeFile, moduleDir, name);
		System.err.println("statusCompile: " + statusCompile);
		final int statusInstall = installModule(moduleDir, name);
		System.err.println("statusInstall: " + statusInstall);
	}

}
