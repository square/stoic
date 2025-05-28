package com.squareup.stoic.android.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.squareup.stoic.android.server.StoicNamedPlugin
import com.squareup.stoic.android.server.StoicPlugin
import com.squareup.stoic.android.server.ensureServer
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.threadlocals.stoic
import kotlin.concurrent.thread

const val TAG = "stoic"

class StoicContentProvider : ContentProvider() {
  private lateinit var authority: String
  private var serverThread: Thread? = null

  override fun attachInfo(context: Context, info: ProviderInfo) {
    super.attachInfo(context, info)
    authority = info.authority
    Log.i(TAG, "attachInfo authority=$authority")
    Log.i(TAG, "query via: adb shell content query --uri content://$authority/stoic")
  }

  override fun onCreate(): Boolean {
    Log.i(TAG, "onCreate")
    return true
  }

  override fun call(
    authority: String,
    method: String,
    arg: String?,
    extras: Bundle?
  ): Bundle? {
    Log.i(TAG, "call: $authority")
    try {
      when (method) {
        "start_server" -> {
          // It's useful to ask debuggable apps to start a LocalSocketServer because we can then
          // forward it over adb.
          if (serverThread == null) {
            serverThread = thread {
              ensureServer(context!!.getDir("stoic", Context.MODE_PRIVATE).absolutePath)
            }
          }
          return Bundle()
        }

        "open_standard" -> {
          val socketPair = ParcelFileDescriptor.createReliableSocketPair()
          val serverSocket = socketPair[0]
          val clientSocket = socketPair[1]
          val stoicDir = context!!.getDir("stoic", Context.MODE_PRIVATE).absolutePath

          thread {
            ParcelFileDescriptor.AutoCloseOutputStream(serverSocket).use { output ->
              ParcelFileDescriptor.AutoCloseInputStream(serverSocket).use { input ->
                // TODO: provide a way for apps to define builtin plugins
                var builtinPlugins = mapOf<String, StoicNamedPlugin>()

                val pluginServer = StoicPlugin(
                  stoicDir,
                  builtinPlugins,
                  input,
                  output,
                )

                pluginServer.pluginMain()
              }
            }
          }

          return Bundle().also {
            it.putParcelable("socket", clientSocket)
          }
        }

        else -> throw IllegalArgumentException("Unrecognized method: $method")
      }

      return super.call(authority, method, arg, extras)
    } catch (e: Throwable) {
      Log.e("stoic", "StoicContentProvider.call threw", e)
      throw e
    }
  }

  override fun call(
    method: String,
    arg: String?,
    extras: Bundle?
  ): Bundle? {
    Log.i(TAG, "call: $method")
    return super.call(method, arg, extras)
  }

  override fun query(
    uri: Uri,
    projection: Array<out String?>?,
    selection: String?,
    selectionArgs: Array<out String?>?,
    sortOrder: String?
  ): Cursor? {
    Log.i(TAG, "query: $uri")
    val path = uri.lastPathSegment ?: return null
    Log.i(TAG, "path: $path")

    return null
  }

  override fun getType(p0: Uri): String? {
    Log.i(TAG, "getType")
    TODO("Not yet implemented")
  }

  override fun insert(
    p0: Uri,
    p1: ContentValues?
  ): Uri? {
    Log.i(TAG, "insert")
    TODO("Not yet implemented")
  }

  override fun delete(
    p0: Uri,
    p1: String?,
    p2: Array<out String?>?
  ): Int {
    Log.i(TAG, "delete")
    TODO("Not yet implemented")
  }

  override fun update(
    p0: Uri,
    p1: ContentValues?,
    p2: String?,
    p3: Array<out String?>?
  ): Int {
    Log.i(TAG, "update")
    TODO("Not yet implemented")
  }
}

