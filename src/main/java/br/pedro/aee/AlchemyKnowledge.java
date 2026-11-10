package br.pedro.aee;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class AlchemyKnowledge implements Component<EntityStore> {
    static final BuilderCodec<AlchemyKnowledge> CODEC = BuilderCodec
        .builder(AlchemyKnowledge.class, AlchemyKnowledge::new)
        .append(new KeyedCodec<>("Emc", Codec.LONG),
            (knowledge, value) -> knowledge.emc = Math.max(0, value), knowledge -> knowledge.emc)
        .add()
        .append(new KeyedCodec<>("Items", Codec.STRING_ARRAY),
            (knowledge, items) -> knowledge.items.addAll(Arrays.asList(items)),
            knowledge -> knowledge.items.stream().sorted().toArray(String[]::new))
        .add()
        .append(new KeyedCodec<>("LastBurn", ItemStack.CODEC),
            (knowledge, stack) -> knowledge.lastBurn = stack, knowledge -> knowledge.lastBurn)
        .add()
        .append(new KeyedCodec<>("ReturnCost", Codec.LONG),
            (knowledge, cost) -> knowledge.returnCost = Math.max(0, cost), knowledge -> knowledge.returnCost)
        .add()
        .build();

    long emc;
    final Set<String> items = new HashSet<>();
    ItemStack lastBurn;
    long returnCost;

    @Override
    public AlchemyKnowledge clone() {
        var copy = new AlchemyKnowledge();
        copy.emc = emc;
        copy.items.addAll(items);
        copy.lastBurn = lastBurn;
        copy.returnCost = returnCost;
        return copy;
    }
}
