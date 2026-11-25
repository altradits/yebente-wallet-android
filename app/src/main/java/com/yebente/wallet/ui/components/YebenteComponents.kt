package com.yebente.wallet.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yebente.wallet.ui.theme.Mono
import com.yebente.wallet.ui.theme.ObsidianPlum
import com.yebente.wallet.ui.theme.Sans
import com.yebente.wallet.util.SatFormat

@Composable
fun StanHeader(title: String, onBack: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    IconButton(onClick = onBack) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Back",
        tint = ObsidianPlum.TextMuted,
      )
    }
    Text(
      text = title,
      fontFamily = Sans,
      fontWeight = FontWeight.Bold,
      fontSize = 16.sp,
      color = ObsidianPlum.TextPrimary,
    )
    Spacer(modifier = Modifier.width(48.dp))
  }
}

@Composable
fun YebenteButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  danger: Boolean = false,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.fillMaxWidth().height(48.dp),
    shape = RoundedCornerShape(16.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = if (danger) ObsidianPlum.StatusError else ObsidianPlum.AccentPrimary,
      contentColor = ObsidianPlum.TextPrimary,
      disabledContainerColor = ObsidianPlum.BgButtonNeutral,
      disabledContentColor = ObsidianPlum.TextDisabled,
    ),
  ) {
    Text(text = label, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 14.sp)
  }
}

@Composable
fun NeutralButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.fillMaxWidth().height(48.dp),
    shape = RoundedCornerShape(16.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = ObsidianPlum.BgButtonNeutral,
      contentColor = ObsidianPlum.TextPrimary,
      disabledContainerColor = ObsidianPlum.BgButtonNeutral,
      disabledContentColor = ObsidianPlum.TextDisabled,
    ),
  ) {
    Text(text = label, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
  }
}

@Composable
fun OptionTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    options.forEachIndexed { index, label ->
      val active = index == selected
      Button(
        onClick = { onSelect(index) },
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = if (active) ObsidianPlum.BgTabActive else ObsidianPlum.BgInput,
          contentColor = if (active) ObsidianPlum.TextPrimary else ObsidianPlum.TextMuted,
        ),
      ) {
        Text(
          text = label,
          fontFamily = Sans,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier
            .border(
              width = 1.dp,
              color = if (active) ObsidianPlum.AccentPrimary else ObsidianPlum.BorderSubtle,
              shape = RoundedCornerShape(12.dp),
            )
            .padding(vertical = 4.dp),
        )
      }
    }
  }
}

@Composable
fun ReadoutRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = label, fontFamily = Sans, fontSize = 12.sp, color = ObsidianPlum.TextMuted)
    Text(
      text = value,
      fontFamily = Mono,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      color = ObsidianPlum.TextSecondary,
    )
  }
}

@Composable
fun AmountText(sats: Long, signed: Boolean = false, large: Boolean = false) {
  Text(
    text = (if (signed) SatFormat.signedSats(sats) else SatFormat.sats(sats)) + " Sats",
    fontFamily = Mono,
    fontWeight = FontWeight.Bold,
    fontSize = if (large) 32.sp else 14.sp,
    color = ObsidianPlum.TextPrimary,
  )
}

@Composable
fun Note(text: String, error: Boolean = false) {
  if (text.isBlank()) return
  Text(
    text = text,
    fontFamily = Sans,
    fontSize = 12.sp,
    color = if (error) ObsidianPlum.StatusError else ObsidianPlum.StatusSuccess,
    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
  )
}
