package dev.dreamingcodes.hcmcp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

object GrokBotHandoff {
  const val PACKAGE = "ai.x.grok.bot"

  fun message(c: IssuedCode, deviceName: String): String {
    val claim = c.claimUrl ?: ""
    return """
      Please add an MCP connector for my Android phone "$deviceName".
      Claim it once with POST $claim and JSON body {"code":"${c.code}"} (or GET $claim?code=${c.code}).
      The response has the connector name, the MCP URL and the Authorization header to use.
      The code works once and expires in 10 minutes.
    """.trimIndent()
  }

  fun share(ctx: Context, text: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      ctx.startActivity(Intent(send).setPackage(PACKAGE))
      true
    } catch (_: ActivityNotFoundException) {
      false
    }
  }
}
