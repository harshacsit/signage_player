package com.signage.player


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log


class SignageBootReceiver : BroadcastReceiver() {


    override fun onReceive(context: Context, intent: Intent) {


        Log.d(
            "SignageBootReceiver",
            "Boot received: ${intent.action}"
        )


        // Do NOT start BootLaunchService here.
        // HOME/Launcher will start MainActivity.
    }
}
