package top.local.bilihdskip;

/** Bounded separable box blur for the small, memory-only card preview snapshot. */
final class CardBlur {
    private CardBlur() {}
    static int[] blur(int[] source, int width, int height, int radius) {
        if (width <= 0 || height <= 0 || width > 192 || height > 192
                || source == null || source.length != width * height || radius < 0 || radius > 32)
            throw new IllegalArgumentException("Invalid card snapshot");
        int[] horizontal = new int[source.length], result = new int[source.length];
        if (radius == 0) return source.clone();
        pass(source, horizontal, width, height, radius, false);
        pass(horizontal, result, width, height, radius, true);
        return result;
    }
    private static void pass(int[] input, int[] output, int width, int height, int radius, boolean vertical) {
        int lines = vertical ? width : height, length = vertical ? height : width;
        int count = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int a = 0, r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = input[index(line, Math.max(0, Math.min(length - 1, i)), width, vertical)];
                a += c >>> 24; r += (c >>> 16) & 255; g += (c >>> 8) & 255; b += c & 255;
            }
            for (int i = 0; i < length; i++) {
                output[index(line, i, width, vertical)] = ((a / count) << 24) | ((r / count) << 16)
                        | ((g / count) << 8) | (b / count);
                int left = input[index(line, Math.max(0, i - radius), width, vertical)];
                int right = input[index(line, Math.min(length - 1, i + radius + 1), width, vertical)];
                a += (right >>> 24) - (left >>> 24);
                r += ((right >>> 16) & 255) - ((left >>> 16) & 255);
                g += ((right >>> 8) & 255) - ((left >>> 8) & 255);
                b += (right & 255) - (left & 255);
            }
        }
    }
    private static int index(int line, int offset, int width, boolean vertical) {
        return vertical ? offset * width + line : line * width + offset;
    }
}
