package io.github.feg55.zarp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ButtonShape = RoundedCornerShape(8.dp)

/** The desktop DarkButton: panel background with a border, or orange when primary. */
@Composable
fun DarkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        modifier = modifier.heightIn(min = 42.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        border = if (primary) null else BorderStroke(1.dp, if (enabled) Zc.Border else Zc.Panel),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Zc.Accent else Zc.Panel,
            contentColor = if (primary) Zc.OnAccent else Zc.Text,
            disabledContainerColor = Zc.Panel,
            disabledContentColor = Zc.TextDisabled,
        ),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Zc.Accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = if (enabled) Zc.Text else Zc.TextDisabled, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = value,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Zc.OnAccent,
                checkedTrackColor = Zc.Accent,
                uncheckedThumbColor = Zc.TextDim,
                uncheckedTrackColor = Zc.Back,
                uncheckedBorderColor = Zc.Border,
            ),
        )
    }
}

@Composable
fun NumberSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Zc.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value.toString(), color = Zc.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        // tick marks only for short ranges; long ones slide freely
        steps = if (range.last - range.first <= 12) range.last - range.first - 1 else 0,
        colors = SliderDefaults.colors(
            thumbColor = Zc.Accent,
            activeTrackColor = Zc.Accent,
            inactiveTrackColor = Zc.Border,
            activeTickColor = Zc.Accent,
            inactiveTickColor = Zc.Border,
        ),
    )
}
