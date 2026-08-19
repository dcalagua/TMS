package com.ebim.tms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the TMS by EBIM backend API.
 *
 * <p>All business operations flow React -&gt; Spring Boot -&gt; PostgreSQL/Supabase. This
 * application owns business authorization, tenancy resolution and every business rule;
 * Supabase owns the platform (PostgreSQL, PostGIS, Auth, RLS as defense in depth).
 */
@SpringBootApplication
public class TmsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(TmsApiApplication.class, args);
    }
}
