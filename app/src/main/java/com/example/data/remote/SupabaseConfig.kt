package com.example.data.remote

object SupabaseConfig {
    const val PROJECT_REF = "zennmoughfhennwgecgo"
    const val BASE_URL = "https://zennmoughfhennwgecgo.supabase.co"
    
    // Public-safe Supabase anon key build constant
    // If not set, can be dynamically configured in runtime settings
    const val DEFAULT_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inplbm5tb3VnaGZoZW5ud2dlY2dvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MDk4MjczODksImV4cCI6MjAyNTQwMzM4OX0.placeholder"

    fun getStoragePublicUrl(bucket: String, path: String): String {
        return "$BASE_URL/storage/v1/object/public/$bucket/$path"
    }
}
