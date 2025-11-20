package com.henriette.downloader;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Connection;

public class DbConnection {
    public static void main(String[] args){
        String dbUrl = "jdbc:postgresql://localhost:5432/webclone";
        String username = "postgres";
        String password = "kelvin";

        try{
            Class.forName("org.postgresql.Driver");
            Connection conn = DriverManager.getConnection(dbUrl,username,password);
            System.out.println("connected successfully to the db");

            WebsiteDownloader downloader = new WebsiteDownloader(conn);
            downloader.downloadWebsite();


            conn.close();
        }
        catch(SQLException e){
            System.out.println("Error connecting to postgres" + e.getMessage());
        }
        catch(ClassNotFoundException n){
            n.printStackTrace();
        }
    }
}
