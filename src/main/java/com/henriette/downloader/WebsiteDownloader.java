package com.henriette.downloader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class WebsiteDownloader {

    private final Connection conn;

    public WebsiteDownloader(Connection conn) {
        this.conn = conn;
    }

    // Entry point
    public void downloadWebsite() {
        try {
            // Safe scanner for user input
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter the website URL (e.g., https://example.com): ");
            String url = scanner.nextLine().trim();

            // Extract domain for folder name
            String domain = url.replaceAll("https?://", "").split("/")[0];

            // Create folder
            File folder = new File(domain);
            if (!folder.exists()) folder.mkdir();
            System.out.println("Created folder: " + folder.getAbsolutePath());

            // Download homepage
            long homepageStart = System.currentTimeMillis();
            Document doc = Jsoup.connect(url).get();
            byte[] homepageBytes = doc.html().getBytes(StandardCharsets.UTF_8);

            File homepageFile = new File(folder, "index.html");
            try (FileOutputStream fos = new FileOutputStream(homepageFile)) {
                fos.write(homepageBytes);
            }

            long homepageEnd = System.currentTimeMillis();
            long homepageElapsed = homepageEnd - homepageStart;
            long homepageKB = homepageBytes.length / 1024;

            // Insert website record
            int websiteId = insertWebsiteRecord(domain, homepageElapsed, homepageKB);

            // Collect all visited links to avoid duplicates
            Set<String> visitedLinks = new HashSet<>();

            // Extract all <a> links
            Elements links = doc.select("a[href]");
            System.out.println("Scanning links...");

            for (Element link : links) {

                String linkUrl = link.absUrl("href");

                if (linkUrl.isEmpty()) continue;
                if (visitedLinks.contains(linkUrl)) continue;

                visitedLinks.add(linkUrl);
                System.out.println("Found: " + linkUrl);

                // Insert basic link record
                insertLinkRecord(linkUrl, websiteId);

                // Only download HTTP/HTTPS
                if (!linkUrl.startsWith("http://") && !linkUrl.startsWith("https://")) {
                    System.out.println("Skipping unsupported link: " + linkUrl);
                    continue;
                }

                // Validate link before download
                if (!isValidURL(linkUrl)) {
                    System.out.println("Invalid or unreachable link: " + linkUrl);
                    continue;
                }

                // Download and update DB
                downloadAndUpdateLink(linkUrl, folder, websiteId);
            }

            // Update totals
            updateWebsiteTotals(websiteId);

            System.out.println("Website download completed successfully.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Validate link using HTTP HEAD request
    private boolean isValidURL(String linkUrl) {
        try {
            URL url = new URL(linkUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int status = connection.getResponseCode();
            return status == 200;

        } catch (Exception e) {
            return false;
        }
    }

    // Insert website record
    private int insertWebsiteRecord(String websiteName, long elapsedTime, long downloadedKB) throws SQLException {
        String sql = """
                INSERT INTO website 
                (website_name, download_start_date_time, download_end_date_time, total_elapsed_time, total_downloaded_kilobytes)
                VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, websiteName);
            ps.setLong(2, elapsedTime);
            ps.setLong(3, downloadedKB);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("id");
        }
        throw new SQLException("Failed to insert website record.");
    }

    // Insert link record
    private void insertLinkRecord(String linkName, int websiteId) throws SQLException {
        String sql = """
                INSERT INTO link (link_name, website_id, total_elapsed_time, total_downloaded_kilobytes)
                VALUES (?, ?, 0, 0)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, linkName);
            ps.setInt(2, websiteId);
            ps.executeUpdate();
        }
    }

    // Download link and update DB
    private void downloadAndUpdateLink(String linkUrl, File folder, int websiteId) {
        long startTime = System.currentTimeMillis();
        long elapsed = 0;
        long kilobytes = 0;

        try {
            byte[] content = Jsoup.connect(linkUrl)
                    .ignoreContentType(true)
                    .timeout(60000)
                    .userAgent("Mozilla/5.0")
                    .execute()
                    .bodyAsBytes();

            long endTime = System.currentTimeMillis();
            elapsed = endTime - startTime;
            kilobytes = content.length / 1024;

            // Create safe filename
            String fileName = linkUrl.replaceAll("https?://", "")
                    .replaceAll("[\\/:*?\"<>|]", "_");

            File file = new File(folder, fileName + ".html");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content);
            }

        } catch (SocketTimeoutException e) {
            System.out.println("Timeout downloading: " + linkUrl);

        } catch (IOException e) {
            System.out.println("Failed to download link: " + linkUrl);

        } finally {
            try {
                String updateSql = """
                    UPDATE link 
                    SET total_elapsed_time = ?, total_downloaded_kilobytes = ?
                    WHERE link_name = ? AND website_id = ?
                """;

                PreparedStatement ps = conn.prepareStatement(updateSql);
                ps.setLong(1, elapsed);
                ps.setLong(2, kilobytes);
                ps.setString(3, linkUrl);
                ps.setInt(4, websiteId);
                ps.executeUpdate();

            } catch (SQLException ex) {
                System.out.println("DB update failed for link: " + linkUrl);
                ex.printStackTrace();
            }
        }
    }

    // Update website totals
    private void updateWebsiteTotals(int websiteId) {
        try {
            String sql = """
                    UPDATE website
                    SET download_end_date_time = CURRENT_TIMESTAMP,
                        total_elapsed_time = COALESCE((SELECT SUM(total_elapsed_time) FROM link WHERE website_id = ?), 0),
                        total_downloaded_kilobytes = COALESCE((SELECT SUM(total_downloaded_kilobytes) FROM link WHERE website_id = ?), 0)
                    WHERE id = ?
                    """;

            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setInt(1, websiteId);
            ps.setInt(2, websiteId);
            ps.setInt(3, websiteId);
            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
