package dev.hannes.virtual_mashine.client.screen;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** A slider that snaps to a fixed list of integer values. */
final class ValueSlider extends AbstractSliderButton {
    private final int[] values;
    private final IntFunction<Component> label;
    private final IntConsumer onChange;

    ValueSlider(int x, int y, int width, int height, int[] values, int current, IntFunction<Component> label, IntConsumer onChange) {
        super(x, y, width, height, Component.empty(), 0);
        this.values = values.length == 0 ? new int[]{current} : values;
        this.label = label;
        this.onChange = onChange;
        setIntValue(current);
    }

    int intValue() {
        int index = (int) Math.round(value * (values.length - 1));
        return values[Math.max(0, Math.min(values.length - 1, index))];
    }

    void setIntValue(int wanted) {
        int best = 0;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - wanted) < Math.abs(values[best] - wanted)) {
                best = i;
            }
        }
        value = values.length == 1 ? 0 : (double) best / (values.length - 1);
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(label.apply(intValue()));
    }

    @Override
    protected void applyValue() {
        int snapped = intValue();
        setIntValue(snapped);
        onChange.accept(snapped);
    }
}
