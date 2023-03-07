/**
 *
 */
package com.publiclibs.awesome.selinux.monitorbuilder;

import static java.nio.file.Files.isRegularFile;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.TrackingRefUpdate;

import com.publiclibs.awesome.selinux.monitorbuilder.MonitorBuilder.WatchDir;

/**
 * @author freedom1b2830
 * @date 2023-марта-07 22:02:22
 */
public class MonitorBuilderThread extends Thread {
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

	long delay = TimeUnit.SECONDS.toMillis(20);
	private final WatchDir watchDir;
	private final Object delayLock = new Object();

	private final Git git;

	protected final List<String> EXTEN_LIST = new ArrayList<>();
	private final Object filesLock = new Object();

	final ConcurrentHashMap<String, Path> map = new ConcurrentHashMap<>();

	public MonitorBuilderThread(final WatchDir watchDirIn, final Path dir) throws IOException {
		this.git = Git.open(dir.toFile());
		this.watchDir = watchDirIn;

		EXTEN_LIST.add("te");
		EXTEN_LIST.add("fc");
		EXTEN_LIST.add("if");
		// EXTEN_LIST.add("restorecon");
	}

	void add2Queue(final Path child) throws InterruptedException {
		if (isRegularFile(child)) {
			synchronized (filesLock) {

				final String fileName = child.toFile().getName();
				if (fileName.contains(".")) {
					final String[] fileParts = fileName.split("\\.");
					final String ext = fileParts[fileParts.length - 1];
					if (EXTEN_LIST.contains(ext)) {
						final String moduleName = fileName.substring(0, fileName.length() - 3);
						final Path moduleDir = child.getParent();
						map.putIfAbsent(moduleName, moduleDir);
					}
				}
			}

		}
	}

	public @Override void run() {
		while (watchDir.active) {
			synchronized (delayLock) {
				try {
					delayLock.wait(delay);
				} catch (final InterruptedException e) {
					e.printStackTrace();
				}
			}
			synchronized (filesLock) {
				try {
					final FetchResult fetch = git.fetch().setForceUpdate(true).call();
					final Collection<TrackingRefUpdate> trackingRefUpdates = fetch.getTrackingRefUpdates();
					if (!trackingRefUpdates.isEmpty()) {
						final PullResult pull = git.pull().call();
						System.err.println("PULL " + pull.toString());
					}
				} catch (final GitAPIException e) {
					e.printStackTrace();
				}
			}
			try {
				for (final Entry<String, Path> entry : map.entrySet()) {
					final String moduleName = entry.getKey();
					final Path moduleDir = entry.getValue();

					map.remove(moduleDir, moduleName);
					rebuildModule(moduleDir, moduleName);
					final Path restorecon = moduleDir.resolve(moduleName + ".restorecon");
					if (Files.exists(restorecon) && Files.isRegularFile(restorecon)) {

						final Set<PosixFilePermission> perms = Files.getPosixFilePermissions(restorecon);
						perms.add(PosixFilePermission.OWNER_EXECUTE);
						perms.add(PosixFilePermission.GROUP_EXECUTE);
						perms.add(PosixFilePermission.OTHERS_EXECUTE);
						Files.setPosixFilePermissions(restorecon, perms);

						System.err.println("RESTORECON " + restorecon);
						final ProcessBuilder makeBuilder = new ProcessBuilder("bash", "-c",
								restorecon.toAbsolutePath().toFile().getAbsolutePath());
						makeBuilder.directory(moduleDir.toFile());
						makeBuilder.redirectErrorStream(true);
						final Process buildProcess = makeBuilder.start();
						try (BufferedReader reader = new BufferedReader(
								new InputStreamReader(buildProcess.getInputStream(), StandardCharsets.UTF_8))) {
							reader.lines().forEachOrdered(System.out::println);
						}
						System.err.println("RESTORECON " + restorecon + " end");
					}

				}
			} catch (final Exception e) {
				e.printStackTrace();
			}

		}

	}

}
