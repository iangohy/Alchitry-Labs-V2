package com.alchitry.labs.ui.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.alchitry.labs.ui.theme.AlchitryTheme

@Preview
@Composable
fun ToolbarButtonPreview() {
    AlchitryTheme {
        ToolbarButton(
            onClick = {},
            icon = painterResource("icons/open.svg"),
            description = "New File"
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolbarButton(
    onClick: () -> Unit,
    icon: Painter,
    description: String,
    colorFilter: ColorFilter? = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
    modifier: Modifier = Modifier
) {
    LocalDensity.current.run {
        val size = 40.dp
        TextTooltipArea(
            text = description
        ) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onBackground
            ) {
                Box(
                    modifier
                        //.background(color = backgroundColor)
                        .clickable(onClick = onClick)
                ) {
                        ProvideTextStyle(
                            value = MaterialTheme.typography.labelLarge
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    painter = icon,
                                    contentDescription = description,
                                    colorFilter = colorFilter,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.padding(6.dp).size(size)
                                )
                            }

                        }
                }
            }
        }
    }
}