package dev.dreamingcodes.hcmcp

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

object GrokBot {
  private const val PACKAGE = "ai.x.grok.bot"

  fun connectorMessage(c: IssuedCode, deviceName: String): String {
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

  fun voiceCallIntents(ctx: Context, agentId: String): List<Intent> = buildList {
    add(Intent(Intent.ACTION_VIEW, Uri.parse("grokbot://app/v1/voice-call?id=$agentId")).setPackage(PACKAGE))
    add(Intent(Intent.ACTION_VIEW, Uri.parse("grokbot://app/v1/agent?id=$agentId")).setPackage(PACKAGE))
    ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.let { add(it) }
    add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(PACKAGE, "$PACKAGE.MainActivity")))
  }

  fun startVoiceCall(ctx: Context, agentId: String): String? = voiceCallIntents(ctx, agentId).firstNotNullOfOrNull { i ->
    if (i.resolveActivity(ctx.packageManager) == null) null
    else runCatching { ctx.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); i.data?.path ?: "launcher" }.getOrNull()
  }
}
