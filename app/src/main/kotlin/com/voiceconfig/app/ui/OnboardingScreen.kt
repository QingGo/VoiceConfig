package com.voiceconfig.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class OnboardingStep(
    val title: String,
    val subtitle: String,
    val points: List<String>,
    val cta: String,
)

private val onboardingSteps = listOf(
    OnboardingStep(
        title = "你好，我是言控",
        subtitle = "你的一句话，我帮你完成",
        points = listOf(
            "每天 8 点自动打开企业微信",
            "帮我点一杯瑞幸咖啡",
            "帮我查母婴用品并比价",
            "回复微信、控制智能家居、远程管理设备",
        ),
        cta = "开始使用",
    ),
    OnboardingStep(
        title = "先给你最基础的能力",
        subtitle = "下面两项只影响体验，不阻塞使用",
        points = listOf(
            "通知权限：任务完成/到点提醒",
            "麦克风权限：语音输入、语音唤醒",
        ),
        cta = "下一步",
    ),
    OnboardingStep(
        title = "高级自动化按需开启",
        subtitle = "读屏、点击、跨 App 操作需要无障碍或 Shizuku",
        points = listOf(
            "未开启时：提醒、定时任务、Agent 对话仍可使用",
            "开启后：自动打开企业微信、回复微信、点咖啡等更复杂",
        ),
        cta = "下一步",
    ),
    OnboardingStep(
        title = "你可以开始了",
        subtitle = "现在去首页说一句话试试",
        points = listOf(
            "简单任务直接说",
            "复杂任务交给智能助手",
            "所有敏感操作都会先经过你确认",
        ),
        cta = "进入首页",
    ),
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val current = onboardingSteps[step]
    val isLast = step == onboardingSteps.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "言控",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "VoiceConfig",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = current.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            current.points.forEach { point ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = point,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(onboardingSteps.size) { index ->
                val active = index == step
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape,
                        ),
                )
            }
        }

        Button(
            onClick = {
                if (isLast) {
                    onFinish()
                } else {
                    step++
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(current.cta, fontWeight = FontWeight.SemiBold)
        }

        if (!isLast) {
            TextButton(
                onClick = onFinish,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text("跳过", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
