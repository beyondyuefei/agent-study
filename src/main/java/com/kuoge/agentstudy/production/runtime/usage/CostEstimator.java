package com.kuoge.agentstudy.production.runtime.usage;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM 调用成本估算器。
 *
 * <p>对应 claw-code Rust 实现：{@code usage.rs}（pricing_for_model + ModelPricing）
 *
 * <p>支持按模型家族定价：
 * <ul>
 *   <li>Claude Sonnet（默认）</li>
 *   <li>Claude Haiku（低价快速）</li>
 *   <li>Claude Opus（高价强能力）</li>
 *   <li>Qwen / GPT（预留扩展）</li>
 * </ul>
 */
public class CostEstimator {

    /**
     * 模型定价（每百万 token 的美元价格）。
     */
    public record ModelPricing(
            double inputCostPerMillion,
            double outputCostPerMillion,
            double cacheCreationCostPerMillion,
            double cacheReadCostPerMillion
    ) {
        public static ModelPricing sonnet() {
            return new ModelPricing(15.0, 75.0, 18.75, 1.5);
        }

        public static ModelPricing haiku() {
            return new ModelPricing(1.0, 5.0, 1.25, 0.1);
        }

        public static ModelPricing opus() {
            return new ModelPricing(15.0, 75.0, 18.75, 1.5);
        }

        public static ModelPricing qwenPlus() {
            // 阿里云 Qwen-plus 约价（2025）
            return new ModelPricing(0.8, 2.0, 0.0, 0.0);
        }
    }

    /**
     * 成本估算结果。
     */
    public record CostEstimate(
            double inputCostUsd,
            double outputCostUsd,
            double cacheCreationCostUsd,
            double cacheReadCostUsd
    ) {
        public double totalCostUsd() {
            return inputCostUsd + outputCostUsd + cacheCreationCostUsd + cacheReadCostUsd;
        }

        public String formattedTotal() {
            return String.format("$%.4f", totalCostUsd());
        }

        public String breakdown() {
            return String.format(
                    "input=$%.4f, output=$%.4f, cache_create=$%.4f, cache_read=$%.4f, total=%s",
                    inputCostUsd, outputCostUsd, cacheCreationCostUsd, cacheReadCostUsd, formattedTotal()
            );
        }
    }

    private final Map<String, ModelPricing> pricingRegistry = new HashMap<>();

    public CostEstimator() {
        // 注册默认定价
        pricingRegistry.put("claude-sonnet", ModelPricing.sonnet());
        pricingRegistry.put("claude-haiku", ModelPricing.haiku());
        pricingRegistry.put("claude-opus", ModelPricing.opus());
        pricingRegistry.put("qwen-plus", ModelPricing.qwenPlus());
    }

    /**
     * 使用默认模型（Sonnet）估算成本。
     */
    public CostEstimate estimate(TokenUsage usage) {
        return estimate(usage, "claude-sonnet");
    }

    /**
     * 使用指定模型估算成本。
     */
    public CostEstimate estimate(TokenUsage usage, String modelAlias) {
        ModelPricing pricing = pricingRegistry.getOrDefault(
                normalizeModelAlias(modelAlias),
                ModelPricing.sonnet()
        );
        return new CostEstimate(
                costForTokens(usage.inputTokens(), pricing.inputCostPerMillion),
                costForTokens(usage.outputTokens(), pricing.outputCostPerMillion),
                costForTokens(usage.cacheCreationInputTokens(), pricing.cacheCreationCostPerMillion),
                costForTokens(usage.cacheReadInputTokens(), pricing.cacheReadCostPerMillion)
        );
    }

    /**
     * 注册自定义模型定价。
     */
    public void registerPricing(String alias, ModelPricing pricing) {
        pricingRegistry.put(normalizeModelAlias(alias), pricing);
    }

    private static double costForTokens(int tokens, double costPerMillion) {
        return tokens / 1_000_000.0 * costPerMillion;
    }

    private static String normalizeModelAlias(String alias) {
        String lower = alias.toLowerCase();
        if (lower.contains("haiku")) return "claude-haiku";
        if (lower.contains("opus")) return "claude-opus";
        if (lower.contains("sonnet")) return "claude-sonnet";
        if (lower.contains("qwen")) return "qwen-plus";
        return lower;
    }
}
