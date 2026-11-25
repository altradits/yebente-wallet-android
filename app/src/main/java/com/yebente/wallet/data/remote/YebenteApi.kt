package com.yebente.wallet.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.yebente.wallet.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface YebenteApi {
  @GET("api/health")
  suspend fun health(): HealthResponse

  @POST("api/lightning/create-invoice")
  suspend fun createInvoice(@Body body: CreateInvoiceRequest): CreateInvoiceResponse

  @GET("api/lightning/invoice-status/{paymentHash}")
  suspend fun invoiceStatus(
    @Path("paymentHash") paymentHash: String,
    @Query("customNodeType") nodeType: String? = null,
    @Query("customNodeUrl") nodeUrl: String? = null,
    @Query("customNodeKey") nodeKey: String? = null,
  ): InvoiceStatusResponse

  @POST("api/lightning/disburse")
  suspend fun disburse(@Body body: DisburseRequest): DisburseResponse
}

object YebenteApiFactory {
  val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
  }

  fun okHttp(): OkHttpClient {
    val builder = OkHttpClient.Builder()
    if (BuildConfig.DEBUG) {
      val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
      builder.addInterceptor(logging)
    }
    return builder.build()
  }

  fun create(baseUrl: String, client: OkHttpClient = okHttp()): YebenteApi {
    val clean = baseUrl.trim().trimEnd('/') + "/"
    return Retrofit.Builder()
      .baseUrl(clean)
      .client(client)
      .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
      .build()
      .create(YebenteApi::class.java)
  }
}
