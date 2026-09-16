package io.github.z1812.hyperdock.compose.page

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.LocalRootBottomBarPadding
import io.github.z1812.hyperdock.compose.component.SectionTitle
import io.github.z1812.hyperdock.compose.component.SettingsAction
import io.github.z1812.hyperdock.compose.component.SettingsActionWithArrow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun AboutPage(
    isCheckingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onOpenBackupRestore: () -> Unit,
) {
    val context = LocalContext.current
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = statusBarPadding,
            end = 16.dp,
            bottom = 28.dp + LocalRootBottomBarPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle(stringResource(R.string.about_developer))
            DeveloperCard()
        }
        item {
            SectionTitle(stringResource(R.string.about_module))
            Card(modifier = Modifier.fillMaxWidth()) {
                SettingsActionWithArrow(
                    title = stringResource(R.string.backup_restore),
                    icon = MiuixIcons.Backup,
                ) {
                    onOpenBackupRestore()
                }
                SettingsAction(
                    title = stringResource(R.string.check_update_action),
                    icon = MiuixIcons.Update,
                    endContent = if (isCheckingUpdate) {
                        {
                            Box(
                                modifier = Modifier.padding(end = 8.dp).size(26.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(size = 20.dp)
                            }
                        }
                    } else {
                        null
                    },
                    enabled = !isCheckingUpdate,
                    onClick = onCheckUpdate,
                )
            }
        }
        item {
            SectionTitle(stringResource(R.string.about_project))
            Card(modifier = Modifier.fillMaxWidth()) {
                SettingsAction(
                    title = stringResource(R.string.github),
                    icon = MiuixIcons.Info,
                    summary = stringResource(R.string.github_summary),
                    endIcon = MiuixIcons.Link,
                    endIconSize = 26.dp,
                ) {
                    context.openUrl(GITHUB_URL)
                }
            }
        }
    }
}

@Composable
private fun DeveloperCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(),
        onClick = { context.openUrl(DEVELOPER_GITHUB_URL) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.about_developer_avatar),
                contentDescription = stringResource(R.string.developer_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.68f), CircleShape),
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = stringResource(R.string.developer_name),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.developer_handle),
                    modifier = Modifier.padding(top = 1.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(width = 10.dp, height = 16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

private fun Context.openUrl(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private const val GITHUB_URL = "https://github.com/1812z/hyperdock"
private const val DEVELOPER_GITHUB_URL = "https://github.com/1812z"