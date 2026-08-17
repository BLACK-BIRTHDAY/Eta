package fuck.andes.ui.components

import androidx.annotation.DrawableRes
import fuck.andes.R
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.provider.ProviderSourceRegistry

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    providerBrandLogoRes(ProviderSourceRegistry.resolve(provider))

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? =
    when (ProviderSourceRegistry.normalize(sourceType)) {
        ProviderSourceTypes.OPENAI -> R.drawable.provider_logo_openai
        ProviderSourceTypes.ANTHROPIC -> R.drawable.provider_logo_anthropic
        ProviderSourceTypes.BAILIAN -> R.drawable.provider_logo_bailian
        ProviderSourceTypes.DEEPSEEK -> R.drawable.provider_logo_deepseek
        ProviderSourceTypes.MOONSHOT -> R.drawable.provider_logo_kimi
        ProviderSourceTypes.MIMO -> R.drawable.provider_logo_mimo
        ProviderSourceTypes.MINIMAX -> R.drawable.provider_logo_minimax
        ProviderSourceTypes.STEPFUN -> R.drawable.provider_logo_stepfun
        ProviderSourceTypes.SILICONFLOW -> R.drawable.provider_logo_siliconflow
        ProviderSourceTypes.OPENROUTER -> R.drawable.provider_logo_openrouter
        else -> null
    }
