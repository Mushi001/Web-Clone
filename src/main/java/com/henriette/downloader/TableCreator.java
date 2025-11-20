package com.henriette.downloader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class TableCreator {
    public static void tableCreator(Connection conn) throws SQLException{
        String websiteTable = "CREATE TABLE IF NOT EXISTS website (" +
                "id SERIAL PRIMARY KEY, " +
                "website_name VARCHAR(255), " +
                "download_start_date_time TIMESTAMP, " +
                "download_end_date_time TIMESTAMP, " +
                "total_elapsed_time BIGINT, " +
                "total_downloaded_kilobytes BIGINT" +
                ");";

        String linkTable = "CREATE TABLE IF NOT EXISTS link (" +
                "id SERIAL PRIMARY KEY, " +
                "link_name VARCHAR(500), " +
                "website_id INT REFERENCES website(id), " +
                "total_elapsed_time BIGINT, " +
                "total_downloaded_kilobytes BIGINT" +
                ");";

        try(Statement stmt = conn.createStatement()){
            stmt.executeUpdate(websiteTable);
            stmt.executeUpdate(linkTable);
            System.out.println("Table created");

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
