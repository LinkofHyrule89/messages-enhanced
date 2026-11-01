package com.ubermicrostudios.messagesenhanced.di

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.util.AvatarRefresher
import com.ubermicrostudios.messagesenhanced.util.NetworkCrossfadeFactory
import com.ubermicrostudios.messagesenhanced.util.UrlStringKeyer
import com.ubermicrostudios.messagesenhanced.util.UrlUriKeyer
import com.ubermicrostudios.messagesenhanced.data.net.AuthInterceptor
import com.ubermicrostudios.messagesenhanced.data.net.MessagesApi
import com.ubermicrostudios.messagesenhanced.data.net.PersistentCookieJar
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.ubermicrostudios.messagesenhanced.data.net.RetryInterceptor
import com.ubermicrostudios.messagesenhanced.data.net.RetryingDns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

private const val RETROFIT_PLACEHOLDER_ORIGIN = "https://placeholder.invalid"

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        cookieJar: PersistentCookieJar,
        authInterceptor: AuthInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .dns(RetryingDns())
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor())
        .addInterceptor(authInterceptor)
        .build()

    /**
     * GitHub client. No cookie jar and no auth interceptor, so the server session
     * is never attached and requests are not rewritten onto the server origin.
     */
    @Provides
    @Singleton
    @Named("updates")
    fun provideUpdateOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "messages-enhanced-native")
                    .build(),
            )
        }
        .build()

    @Provides
    @Singleton
    @Named("sse")
    fun provideSseOkHttpClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
        // Longer than the server's 25s heartbeat so a quiet-but-alive stream stays up,
        // and a dead one fails instead of hanging with readTimeout 0.
        .readTimeout(70, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, prefs: AppPreferences): Retrofit {
        val json = JsonConfig.json
        val contentType = "application/json".toMediaType()
        // Retrofit requires an absolute base URL at construction. Requests are rewritten
        // onto the saved server. This host is never contacted.
        val origin = prefs.serverUrl.trim().trimEnd('/').ifBlank { RETROFIT_PLACEHOLDER_ORIGIN }
        return Retrofit.Builder()
            .baseUrl("$origin/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideMessagesApi(retrofit: Retrofit): MessagesApi =
        retrofit.create(MessagesApi::class.java)

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        client: OkHttpClient,
        avatarRefresher: AvatarRefresher,
    ): ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(client)
        .eventListener(avatarRefresher)
        .transitionFactory(NetworkCrossfadeFactory())
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.20)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache"))
                .maxSizeBytes(250L * 1024 * 1024)
                .build()
        }
        // Avatar and media responses are authenticated and often marked uncacheable.
        .respectCacheHeaders(false)
        .components {
            if (Build.VERSION.SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            add(UrlStringKeyer())
            add(UrlUriKeyer())
        }
        .build()
}
