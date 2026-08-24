package com.moozik.player

import android.content.Context
import com.moozik.player.audio.EqController
import com.moozik.player.audio.MoozikPlayer

/**
 * Process-wide home of the player + EQ so the UI and the foreground service
 * always share one instance regardless of who comes alive first.
 */
object PlayerBox {

    @Volatile var eq: EqController? = null; private set
    @Volatile var player: MoozikPlayer? = null; private set

    fun ensure(context: Context): Pair<MoozikPlayer, EqController> {
        val app = context.applicationContext
        if (eq == null) eq = EqController(app)
        if (player == null) player = MoozikPlayer(app, eq)
        return player!! to eq!!
    }
}
