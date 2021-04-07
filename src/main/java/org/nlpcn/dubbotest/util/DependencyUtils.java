package org.nlpcn.dubbotest.util;

import com.alibaba.dubbo.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author : qihang.liu
 * @date 2021-04-06
 */
public class DependencyUtils {
    private static final Logger LOG = LoggerFactory.getLogger(DependencyUtils.class);
    private static Pattern pattern = Pattern.compile(" \\w+:\\w+:\\w+:\\w+");


    public static List<String> parseJarDependency(String localRepository, String groupId, String artifactId, String version) throws IOException {
        Path path = Paths.get(localRepository, StringUtils.split(groupId, '.')).resolve(artifactId).resolve(version);

        List<String> commands = new LinkedList<>();
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            commands.add("cmd");
            commands.add("/c");
        }
        commands.addAll(Arrays.asList("mvn", "-f", "pom.xml"));

        commands.add("dependency:resolve");

        List<String> dependencyList;

        Path dir = Files.createTempDirectory(null);
        LOG.info("prepare to parse artifact[{}:{}:{}] after creating temp directory[{}]", groupId, artifactId, version, dir);
        try {
            Files.write(dir.resolve("pom.xml"), Arrays.asList(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">",
                    "<modelVersion>4.0.0</modelVersion><groupId>org.nlpcn</groupId><artifactId>dubbotest</artifactId><version>0.0.1-SNAPSHOT</version><dependencies>",
                    String.format("<dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></dependency>",
                            groupId, artifactId, version),
                    "</dependencies></project>"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.createDirectories(path);
                dependencyList = executeMvnDependencyResolve(dir.toFile(), commands.toArray(new String[0]));
            } catch (Exception ex) {
                FileSystemUtils.deleteRecursively(path);
                throw new IllegalStateException(ex);
            }
        } finally {
            boolean ret = FileSystemUtils.deleteRecursively(dir);
            LOG.info("parse [{}:{}:{}] end, delete temp directory[{}]: {}", groupId, artifactId, version, dir, ret);
        }

        return dependencyList;
    }

    public static String[] getMavenIndexer(String str) {
        String[] indexer = com.alibaba.dubbo.common.utils.StringUtils.split(str, ':');
        if (indexer.length < 3) {
            throw new IllegalArgumentException("Invalid maven indexer " + str);
        }
        return indexer;
    }

    public static Path getMavenPath(String localRepository, String groupId, String artifactId, String version) {
        return Paths.get(localRepository, StringUtils.split(groupId, '.')).resolve(artifactId).resolve(version);
    }

    private static List<String> executeMvnDependencyResolve(File baseDir, String... args) throws IOException, InterruptedException {
        LOG.info("exec: {}", Arrays.toString(args));

        List<String> dependencyList = new ArrayList<>();

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(baseDir);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try {
            LOG.info("Process started !");

            String line;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                while ((line = in.readLine()) != null) {
                    String dependency = parseMavenDependencyResolveLine(line);
                    if (!StringUtils.isEmpty(dependency)) {
                        LOG.info("Found dependency: {}", dependency);
                        String[] arr = getMavenIndexer(dependency);
                        dependencyList.add(arr[0] + ":" + arr[1] + ":" + arr[3]);
                    }
                }
            }

            proc.waitFor();

            LOG.info("Process ended !");
        } finally {
            proc.destroy();
        }

        return dependencyList;
    }

    private static String parseMavenDependencyResolveLine(String line) {
//        Matcher matcher = pattern.matcher(line);
//        if (!matcher.matches()) {
//            return "";
//        }
        String key = "[\u001B[1;34mINFO\u001B[m]    ";
        if (!line.startsWith(key)) {
            return "";
        }
        return line.substring(line.indexOf("  ")).trim();
    }
}
