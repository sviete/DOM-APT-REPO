package com.termux.bootstrap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import com.termux.repotools.Packages;
import com.termux.util.IOUtils;
import com.termux.util.ShellUtils;

/**
 * Checks http://termux.net/dists/stable/main/binary-${ARCH}/Packages.xz for the {@link #BOOTSTRAP_PACKAGES} to download and merges them into a single zip file
 * which is published at https://termux.net/bootstrap/bootstrap_${ARCH}.zip.
 */
public class BootstrapZipBuilder {

	private static final String PREFIX = "/data/data/com.termux/files/usr/";

	private static final String REPO_BASE_URL = "http://termux.net/";

	private static final String[] BOOTSTRAP_PACKAGES = {
			// Having bash as shell:
			"bash", "readline", "ncurses", "command-not-found", "termux-tools",
			// Needed for bin/sh:
			"dash",
			// For use by dpkg and apt:
			"liblzma",
			// Needed by dpkg:
			"libandroid-support",
			// dpkg uses tar (and wants 'find' in path for some operations):
			"busybox",
			// apt uses STL:
			"libc++",
			// gnupg for package verification:
			"gpgv",
			// For package management:
			"dpkg", "apt" };

	private static MessageDigest MD5;
	private static final byte[] MD5_BUFFER = new byte[8096];

	static {
		try {
			MD5 = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static void createBootStraps() throws Exception {
		createBootstrap(Packages.ARCH_ARM);
		createBootstrap(Packages.ARCH_X86);
		createBootstrap(Packages.ARCH_AARCH64);
		createBootstrap(Packages.ARCH_X86_64);
	}

	/** The base url where Packages.xz and .deb files can be found under. */
	private static String getArchBaseUrl(String arch) {
		return REPO_BASE_URL + "dists/stable/main/binary-" + arch;
	}

	public static Map</* packageName= */String, Map</* fieldName= */String, /* fieldValue= */String>> parsePackages(String arch) throws Exception {
		URL packagesXzUrl = new URL(getArchBaseUrl(arch) + "/Packages.xz");
		System.out.println("Downloading Packages.xz (" + packagesXzUrl + ")...");
		HttpURLConnection con = (HttpURLConnection) packagesXzUrl.openConnection();
		try (InputStream in = con.getInputStream()) {
			return Packages.parsePackagesStream(new XZCompressorInputStream(in), packagesXzUrl.toString());
		} catch (IOException e) {
			System.out.println("ERROR: " + IOUtils.readAll(con.getErrorStream()));
			throw e;
		}
	}

	public static void createBootstrap(String currentArch) throws Exception {
		File bootstrapDir = new File(System.getProperty("user.home") + "/tmp/termux-bootstrap-zip");
		System.out.println("# Creating bootstrap zip for arch " + currentArch + " in " + bootstrapDir);
		ShellUtils.exec("rm -Rf " + bootstrapDir.getAbsolutePath(), "mkdir -p " + bootstrapDir.getAbsolutePath());

		File prefixDir = new File(bootstrapDir + PREFIX);
		prefixDir.mkdirs();

		Map<String, Map<String, String>> packagesMap = parsePackages(currentArch);
		packagesMap.putAll(parsePackages("all")); // termux-tools is in "all" arch.

		HashSet<String> symlinks = new HashSet<>();
		Map<String, SortedSet<String>> packagesContentMap = new HashMap<>();

		try (FileWriter symlinksWriter = new FileWriter(new File(prefixDir, "SYMLINKS.txt"))) {
			for (String pkg : BOOTSTRAP_PACKAGES) {
				Map<String, String> pkgMap = packagesMap.get(pkg);
				if (pkgMap == null) ShellUtils.die("Did not find bootstrap package '" + pkg + "'. Available: " + packagesMap);
				@SuppressWarnings("null")
				String filenameValue = pkgMap.get("Filename");
				if (filenameValue == null) ShellUtils.die("Did not find Filename field for package '" + pkg);

				String pkgUrl = REPO_BASE_URL + filenameValue;
				System.out.println("Downloading " + pkgUrl + "...");

				final InputStream is = new URL(pkgUrl).openStream();
				final ArArchiveInputStream debInputStream = (ArArchiveInputStream) new ArchiveStreamFactory().createArchiveInputStream("ar", is);
				ArArchiveEntry entry = null;

				boolean foundDataTar = false;
				while ((entry = (ArArchiveEntry) debInputStream.getNextEntry()) != null) {
					if (entry.getName().equals("data.tar.xz")) {
						foundDataTar = true;
						InputStream zipInput = new XZCompressorInputStream(debInputStream);
						TarArchiveInputStream dataInput = (TarArchiveInputStream) new ArchiveStreamFactory().createArchiveInputStream("tar", zipInput);
						TarArchiveEntry tarEntry = null;
						while ((tarEntry = (TarArchiveEntry) dataInput.getNextEntry()) != null) {
							String path = tarEntry.getName();
							if (path.endsWith("/")) continue;
							if (path.startsWith(".")) path = path.substring(1);

							SortedSet<String> contents = packagesContentMap.get(pkg);
							if (contents == null) {
								contents = new TreeSet<>();
								packagesContentMap.put(pkg, contents);
							}
							contents.add(path);
							if (tarEntry.isSymbolicLink()) symlinks.add(path);

							File output = new File(bootstrapDir.getAbsolutePath() + path);
							ShellUtils.exec("mkdir -p " + output.getParent());
							if (tarEntry.isLink()) {
								ShellUtils.die("Found a hard link at " + tarEntry.getLinkName() + " - will not work on Android M");
							} else if (tarEntry.isSymbolicLink()) {
								String oldPath = new File(new File(path).getParent() + "/" + tarEntry.getLinkName()).getCanonicalPath();
								oldPath = oldPath.substring(PREFIX.length(), oldPath.length());

								String linkPath = path.substring(PREFIX.length(), path.length());

								String pathRelative = Paths.get(linkPath).relativize(Paths.get(oldPath)).toString();
								// Strip away leading "../" which relativize() adds:
								pathRelative = pathRelative.substring(3, pathRelative.length());

								String linkLine = pathRelative + "←" + linkPath + "\n";

								symlinksWriter.write(linkLine);
							} else {
								IOUtils.copyAndClose(dataInput, new FileOutputStream(output));
							}
						}
					}
				}
				if (!foundDataTar) {
					System.err.println("ERROR: No data tar - exiting");
					System.exit(1);
				}
			}
		}

		System.out.println("Setting up environment...");
		// XXX: These directories should perhaps be created by apt and dpkg themselves:
		ShellUtils.exec(prefixDir, "mkdir -p etc/apt/preferences.d etc/apt/apt.conf.d var/cache/apt/archives/partial var/log/apt tmp"
				+ " var/lib/dpkg/info var/lib/dpkg/triggers var/lib/dpkg/updates");

		String fullVarLibPath = bootstrapDir + PREFIX + "/var/lib";
		Arrays.asList("dpkg/info", "dpkg/triggers", "dpkg/updates", "apt/lists/partial").stream()
				.forEach(f -> ShellUtils.exec("mkdir -p " + fullVarLibPath + "/" + f));
		try (PrintWriter writer = new PrintWriter(new File(fullVarLibPath + "/dpkg/status"))) {
			for (String bootPkg : BOOTSTRAP_PACKAGES) {
				Map<String, String> pkgFieldsMap = packagesMap.get(bootPkg);
				for (Map.Entry<String, String> line : pkgFieldsMap.entrySet()) {
					String key = line.getKey();
					switch (key) {
					case "Filename":
					case "MD5sum":
					case "MD5Sum":
					case "SHA1":
					case "SHA256":
					case "Size":
						// These are not for the status file.
						continue;
					}
					writer.println(line.getKey() + ": " + line.getValue());
				}
				writer.println("Status: install ok installed");
				writer.println();
			}
		}
		ShellUtils.exec("touch " + fullVarLibPath + "/dpkg/available");

		for (String bootPkg : BOOTSTRAP_PACKAGES) {
			try (PrintWriter writer = new PrintWriter(new File(fullVarLibPath + "/dpkg/info/" + bootPkg + ".list"))) {
				Set<String> alreadyWrittenPaths = new HashSet<>();
				Set<String> files = packagesContentMap.get(bootPkg);
				for (String file : files) {
					writer.println(file);
					alreadyWrittenPaths.add(file);
				}
				// dpkg wants folders to be present as well:
				for (String file : files) {
					int indexOfSlash = file.indexOf('/', 1);
					while (indexOfSlash > 0) {
						String subString = file.substring(0, indexOfSlash);
						if (!alreadyWrittenPaths.contains(subString)) {
							writer.println(subString);
							alreadyWrittenPaths.add(subString);
						}
						indexOfSlash = file.indexOf('/', indexOfSlash + 1);
					}
				}

				// The md5 hash sums can be verified using "dpkg --verify":
				try (PrintWriter md5Writer = new PrintWriter(new File(fullVarLibPath + "/dpkg/info/" + bootPkg + ".md5sums"))) {
					for (String file : files) {
						if (symlinks.contains(file)) {
							// Symlinks are not in the .md5sums file.
						} else {
							File fsFile = new File(bootstrapDir + "" + file);
							String md5 = md5Sum(fsFile.getCanonicalFile());
							md5Writer.println(md5 + "  " + file.substring(1));
						}
					}
				}
			}
		}

		String productionZipName = "bootstrap-" + currentArch + ".zip";
		String newZipName = "new-bootstrap-" + currentArch + ".zip";
		String zipCmd = "zip -r " + newZipName + " .";
		System.out.println("Creating bootstrap zip (" + zipCmd + ")...");
		ShellUtils.exec(prefixDir, zipCmd);

		String HOST = "xx.xx.xx.xx";
		String BOOTSTRAP_FOLDER = "/var/www/html/bootstrap/";

		String scpCommand = "scp " + newZipName + " " + HOST + ":" + BOOTSTRAP_FOLDER;
		System.out.println("Uploading bootstrap zip (" + scpCommand + ")...");
		ShellUtils.exec(prefixDir, scpCommand);

		String backupCommand = "ssh " + HOST + " cp " + BOOTSTRAP_FOLDER + productionZipName +
				" " + BOOTSTRAP_FOLDER + productionZipName + ".bak";
		System.out.println("Taking backup of current bootstrap zip file (" + backupCommand + ")...");
		ShellUtils.exec(prefixDir, backupCommand);

		String installCommand = "ssh " + HOST + " mv " + BOOTSTRAP_FOLDER + newZipName +
				" " + BOOTSTRAP_FOLDER + productionZipName;
		System.out.println("Atomically replacing old zip file (" + installCommand + ")...");
		ShellUtils.exec(prefixDir, installCommand);

        String purgeCommand = "termux-purge-bootstrap";
        System.out.println("Purging cloudflare cache (" + purgeCommand + ")...");
        ShellUtils.exec(purgeCommand);
    }

	/** Just like the md5sum(1) utility. */
	public static String md5Sum(File file) throws Exception {
		MD5.reset();
		int read;
		try (InputStream in = new FileInputStream(file)) {
			while ((read = in.read(MD5_BUFFER)) != -1)
				MD5.update(MD5_BUFFER, 0, read);
		}
		String hexEncoded = new BigInteger(1, MD5.digest()).toString(16);
		if (hexEncoded.length() == 32) {
			return hexEncoded;
		} else {
			StringBuilder sb = new StringBuilder();
			while (sb.length() + hexEncoded.length() < 32) {
				sb.append('0');
			}
			sb.append(hexEncoded);
			return sb.toString();
		}
	}

}
