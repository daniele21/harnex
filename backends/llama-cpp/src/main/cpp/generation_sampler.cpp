#include "generation_sampler.h"

#include <type_traits>

namespace {

template <typename>
inline constexpr bool unsupported_penalties_signature = false;

template <typename PenaltiesFn>
llama_sampler* init_penalties_compat(
    PenaltiesFn penalties_fn,
    std::int32_t vocabulary_size,
    std::int32_t repeat_last_n,
    float repeat_penalty,
    float frequency_penalty,
    float presence_penalty
) {
    if constexpr (
        std::is_invocable_r_v<
            llama_sampler*,
            PenaltiesFn,
            std::int32_t,
            float,
            float,
            float
        >
    ) {
        return penalties_fn(
            repeat_last_n,
            repeat_penalty,
            frequency_penalty,
            presence_penalty
        );
    } else if constexpr (
        std::is_invocable_r_v<
            llama_sampler*,
            PenaltiesFn,
            std::int32_t,
            std::int32_t,
            float,
            float,
            float
        >
    ) {
        if (vocabulary_size <= 0) {
            return nullptr;
        }
        return penalties_fn(
            vocabulary_size,
            repeat_last_n,
            repeat_penalty,
            frequency_penalty,
            presence_penalty
        );
    } else {
        static_assert(
            unsupported_penalties_signature<PenaltiesFn>,
            "Unsupported llama_sampler_init_penalties signature"
        );
    }
}

struct SampledTokenAcceptanceContext final {
    llama_sampler* inner = nullptr;
    llama_token sampled_token = LLAMA_TOKEN_NULL;
    bool sample_apply_pending = false;
    bool duplicate_accept_pending = false;
};

SampledTokenAcceptanceContext* sampled_token_context(llama_sampler* sampler) {
    return static_cast<SampledTokenAcceptanceContext*>(sampler->ctx);
}

const SampledTokenAcceptanceContext* sampled_token_context(const llama_sampler* sampler) {
    return static_cast<const SampledTokenAcceptanceContext*>(sampler->ctx);
}

const char* sampled_token_acceptance_name(const llama_sampler* /* sampler */) {
    return "harness-sampled-token-accept-once";
}

void sampled_token_acceptance_accept(llama_sampler* sampler, llama_token token) {
    auto* context = sampled_token_context(sampler);
    if (context->sample_apply_pending) {
        llama_sampler_accept(context->inner, token);
        context->sampled_token = token;
        context->sample_apply_pending = false;
        context->duplicate_accept_pending = true;
        return;
    }

    if (context->duplicate_accept_pending && token == context->sampled_token) {
        context->duplicate_accept_pending = false;
        return;
    }

    context->duplicate_accept_pending = false;
    llama_sampler_accept(context->inner, token);
}

void sampled_token_acceptance_apply(llama_sampler* sampler, llama_token_data_array* candidates) {
    auto* context = sampled_token_context(sampler);
    context->sample_apply_pending = true;
    context->duplicate_accept_pending = false;
    llama_sampler_apply(context->inner, candidates);
}

void sampled_token_acceptance_reset(llama_sampler* sampler) {
    auto* context = sampled_token_context(sampler);
    context->sampled_token = LLAMA_TOKEN_NULL;
    context->sample_apply_pending = false;
    context->duplicate_accept_pending = false;
    llama_sampler_reset(context->inner);
}

llama_sampler* sampled_token_acceptance_clone(const llama_sampler* sampler);

void sampled_token_acceptance_free(llama_sampler* sampler) {
    auto* context = sampled_token_context(sampler);
    llama_sampler_free(context->inner);
    delete context;
}

llama_sampler_i sampled_token_acceptance_interface = {
    /* .name = */ sampled_token_acceptance_name,
    /* .accept = */ sampled_token_acceptance_accept,
    /* .apply = */ sampled_token_acceptance_apply,
    /* .reset = */ sampled_token_acceptance_reset,
    /* .clone = */ sampled_token_acceptance_clone,
    /* .free = */ sampled_token_acceptance_free,
    /* .backend_init = */ nullptr,
    /* .backend_accept = */ nullptr,
    /* .backend_apply = */ nullptr,
    /* .backend_set_input = */ nullptr,
    /* .backend_reset = */ nullptr,
    /* .copy_state = */ nullptr,
};

llama_sampler* wrap_sampled_token_acceptance(llama_sampler* inner) {
    if (inner == nullptr) {
        return nullptr;
    }
    auto* context = new SampledTokenAcceptanceContext();
    context->inner = inner;
    return llama_sampler_init(&sampled_token_acceptance_interface, context);
}

llama_sampler* sampled_token_acceptance_clone(const llama_sampler* sampler) {
    const auto* source = sampled_token_context(sampler);
    llama_sampler* inner_clone = llama_sampler_clone(source->inner);
    llama_sampler* clone = wrap_sampled_token_acceptance(inner_clone);
    if (clone == nullptr) {
        llama_sampler_free(inner_clone);
        return nullptr;
    }
    auto* clone_context = sampled_token_context(clone);
    clone_context->sampled_token = source->sampled_token;
    clone_context->sample_apply_pending = source->sample_apply_pending;
    clone_context->duplicate_accept_pending = source->duplicate_accept_pending;
    return clone;
}

}  // namespace

GenerationSampler normalize_sampled_token_acceptance(GenerationSampler sampler) {
    if (sampler == nullptr) {
        return {nullptr, llama_sampler_free};
    }
    llama_sampler* wrapped = wrap_sampled_token_acceptance(sampler.release());
    return {wrapped, llama_sampler_free};
}

GenerationSampler create_generation_sampler(
    const llama_vocab* vocab,
    float temperature,
    float top_p,
    std::int32_t top_k,
    float min_p,
    float presence_penalty,
    float repeat_penalty,
    std::int32_t repeat_last_n,
    std::uint32_t seed,
    const std::string& grammar,
    std::int32_t vocabulary_size_override
) {
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sampler == nullptr) {
        return {nullptr, llama_sampler_free};
    }

    if (!grammar.empty()) {
        llama_sampler* grammar_sampler = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (grammar_sampler == nullptr) {
            llama_sampler_free(sampler);
            return {nullptr, llama_sampler_free};
        }
        llama_sampler_chain_add(sampler, grammar_sampler);
    }

    const std::int32_t vocabulary_size = vocabulary_size_override > 0
        ? vocabulary_size_override
        : (vocab != nullptr ? llama_vocab_n_tokens(vocab) : 0);
    llama_sampler* penalties_sampler = init_penalties_compat(
        &llama_sampler_init_penalties,
        vocabulary_size,
        repeat_last_n,
        repeat_penalty,
        0.0F,
        presence_penalty
    );
    if (penalties_sampler == nullptr) {
        llama_sampler_free(sampler);
        return {nullptr, llama_sampler_free};
    }
    llama_sampler_chain_add(sampler, penalties_sampler);

    if (temperature <= 0.0F) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        if (min_p > 0.0F) {
            llama_sampler_chain_add(sampler, llama_sampler_init_min_p(min_p, 1));
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(seed));
    }

    return normalize_sampled_token_acceptance({sampler, llama_sampler_free});
}
