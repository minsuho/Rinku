package de.keksuccino.rinku;

import de.keksuccino.rinku.binarydownload.RinkuDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static de.keksuccino.rinku.RinkuInstallerTestSupport.COMMIT_A;
import static de.keksuccino.rinku.RinkuInstallerTestSupport.PLATFORM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GTWebUI fork: the launcher leaves {@code jcef-v1/<platform>/<commit>.tar.gz} and its .sha256, the game installs it. */
class RinkuLauncherArchiveTest {

    @TempDir
    Path temporaryDirectory;

    private static final RinkuDownloader.ArtifactDownloader NO_NETWORK = (url, outputFile, maxBytes) -> {
        throw new AssertionError("must not download: " + url);
    };

    private Path archive() {
        return RinkuInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A).resolveSibling(COMMIT_A + ".tar.gz");
    }

    private Path checksum() {
        return archive().resolveSibling(COMMIT_A + ".tar.gz.sha256");
    }

    private void placeArchive(byte[] bytes, String digest) throws IOException {
        Files.createDirectories(archive().getParent());
        Files.write(archive(), bytes);
        if (digest != null) {
            RinkuInstallerTestSupport.writeChecksum(checksum(), digest);
        }
    }

    private RinkuDownloader offline(String version) {
        return new RinkuDownloader(RinkuDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, RinkuDownloader.DownloadPolicy.defaults(), temporaryDirectory, NO_NETWORK, RinkuInstallerTestSupport.extractor(version));
    }

    @Test
    void launcherArchiveInstallsWithoutTheNetworkAndIsDeleted() throws Exception {
        byte[] bytes = RinkuInstallerTestSupport.archiveBytes("launcher");
        placeArchive(bytes, RinkuInstallerTestSupport.sha256(bytes));

        RinkuDownloader.InstallationResult result = offline("launcher").installOrUpdate(true);

        assertFalse(result.downloaded());
        assertEquals("launcher", RinkuInstallerTestSupport.readVersion(result.installationDirectory()));
        assertTrue(Files.isRegularFile(result.installationDirectory().resolve(RinkuJcefInstallationValidator.COMPLETE_MARKER_FILE)));
        assertFalse(Files.exists(archive()));
        assertFalse(Files.exists(checksum()));
    }

    @Test
    void completedInstallationIsReusedAndALeftoverArchiveIsNotOpened() throws Exception {
        byte[] bytes = RinkuInstallerTestSupport.archiveBytes("first");
        placeArchive(bytes, RinkuInstallerTestSupport.sha256(bytes));
        offline("first").installOrUpdate(true);
        placeArchive(RinkuInstallerTestSupport.archiveBytes("second"), "0".repeat(64));

        RinkuDownloader.InstallationResult result = offline("second").installOrUpdate(true);

        assertEquals("first", RinkuInstallerTestSupport.readVersion(result.installationDirectory()));
        assertTrue(Files.exists(archive()));
    }

    @Test
    void wrongChecksumFailsWithSkipDownloadAndKeepsTheArchive() throws Exception {
        placeArchive(RinkuInstallerTestSupport.archiveBytes("corrupt"), "0".repeat(64));

        assertThrows(IOException.class, () -> offline("corrupt").installOrUpdate(true));

        assertTrue(Files.exists(archive()));
        assertFalse(Files.exists(RinkuInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A)));
    }

    @Test
    void missingChecksumFailsWithSkipDownload() throws Exception {
        placeArchive(RinkuInstallerTestSupport.archiveBytes("unchecked"), null);

        assertThrows(IOException.class, () -> offline("unchecked").installOrUpdate(true));

        assertFalse(Files.exists(RinkuInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A)));
    }

    @Test
    void wrongChecksumFallsBackToTheDownloadWhenAllowed() throws Exception {
        placeArchive(RinkuInstallerTestSupport.archiveBytes("corrupt"), "0".repeat(64));
        byte[] downloaded = RinkuInstallerTestSupport.archiveBytes("downloaded");

        RinkuDownloader.InstallationResult result = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, downloaded, RinkuInstallerTestSupport.sha256(downloaded), RinkuInstallerTestSupport.extractor("downloaded")).installOrUpdate(false);

        assertTrue(result.downloaded());
        assertEquals("downloaded", RinkuInstallerTestSupport.readVersion(result.installationDirectory()));
    }
}
