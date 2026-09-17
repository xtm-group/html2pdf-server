package eu.xtrf.html2pdf.server.converter.service;

import eu.xtrf.html2pdf.server.converter.service.BackgroundImageOptimizer.PreparedBackground;
import eu.xtrf.html2pdf.server.converter.service.BackgroundImageOptimizer.PreparedBackground.Verdict;
import org.testng.annotations.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.decode;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.dimensionsOf;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.jpeg;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.noisePng;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.png;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.pngHeaderOnly;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * XDEV-6073. The tier table and its two boundaries, the cache policy, and the two runtime properties
 * the fix promises: that nothing is written to disk and that a hostile property value degrades.
 *
 * Thresholds are passed to the constructor rather than set as system properties, so every case runs
 * against a 400x300 fixture (120 000 pixels) and no test has to build a 30-megapixel image.
 */
public class BackgroundImageOptimizerTest {

    private static final long PIXELS = 120_000L;
    private static final long TARGET = 30_000L;
    private static final String CORRELATION_ID = "xdev-6073";

    private static BackgroundImageOptimizer optimizer(long optimizeAbovePixels, long maxPixels) {
        return new BackgroundImageOptimizer(true, optimizeAbovePixels, TARGET, maxPixels, BackgroundImageOptimizer.DEFAULT_MAX_CACHE_BYTES);
    }

    private static BackgroundImageOptimizer optimizerWithBudget(long maxCacheBytes) {
        return new BackgroundImageOptimizer(true, PIXELS - 1, TARGET, 1_000_000L, maxCacheBytes);
    }

    /**
     * The byte size of the PNG the optimizer serves for {@code source}, measured on a throwaway
     * instance so a test can size its budget from the transform rather than guess at PNG output.
     */
    private static long transformedSize(byte[] source) {
        PreparedBackground prepared = optimizer(PIXELS - 1, 1_000_000L).optimize(source, CORRELATION_ID);
        assertEquals(prepared.verdict(), Verdict.DOWNSCALED, "the fixture must be one the optimizer transforms");
        return prepared.bytes().length;
    }

    @Test
    public void should_leave_an_image_below_the_optimisation_threshold_untouched() {
        // given
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground prepared = optimizer(PIXELS + 1, 1_000_000L).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.UNCHANGED);
        assertSame(prepared.bytes(), source, "an untouched image must be served back as the caller's own array");
        assertEquals(prepared.originalWidth(), 400);
        assertEquals(prepared.originalHeight(), 300);
    }

    @Test
    public void should_leave_an_image_exactly_at_the_optimisation_threshold_untouched() {
        // given
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground prepared = optimizer(PIXELS, 1_000_000L).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.UNCHANGED, "the optimisation boundary is inclusive");
    }

    @Test
    public void should_downscale_an_image_one_pixel_above_the_optimisation_threshold() {
        // given
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground prepared = optimizer(PIXELS - 1, 1_000_000L).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.DOWNSCALED);
        int[] served = dimensionsOf(prepared.bytes());
        assertTrue((long) served[0] * served[1] <= TARGET,
                "served raster " + served[0] + "x" + served[1] + " must be at or below the target of " + TARGET);
        assertTrue((long) served[0] * served[1] < PIXELS, "a downscale that does not shrink the raster is not a downscale");
        assertEquals(prepared.originalWidth(), 400, "the theme's own geometry must survive the downscale");
        assertEquals(prepared.originalHeight(), 300);
    }

    @Test
    public void should_keep_transparency_when_it_downscales() {
        // given a fixture whose left half is fully transparent
        byte[] source = png(400, 300, true);

        // when
        PreparedBackground prepared = optimizer(PIXELS - 1, 1_000_000L).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.DOWNSCALED);
        BufferedImage served = decode(prepared.bytes());
        assertTrue(served.getColorModel().hasAlpha(), "a transparent background must not be flattened onto a colour");
        int left = served.getRGB(served.getWidth() / 4, served.getHeight() / 2) >>> 24;
        int right = served.getRGB(served.getWidth() * 3 / 4, served.getHeight() / 2) >>> 24;
        assertEquals(left, 0, "a pixel that was transparent must still be transparent");
        assertEquals(right, 255, "a pixel that was opaque must still be opaque");
    }

    @Test
    public void should_not_substitute_an_image_exactly_at_the_hard_cap() {
        // given
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground prepared = optimizer(PIXELS + 1, PIXELS).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.UNCHANGED, "the hard cap is exclusive: substitution starts one pixel above it");
    }

    @Test
    public void should_substitute_a_blank_background_above_the_hard_cap() {
        // given
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground prepared = optimizer(PIXELS + 1, PIXELS - 1).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.SUBSTITUTED);
        assertNotSame(prepared.bytes(), source);
        int[] served = dimensionsOf(prepared.bytes());
        assertEquals(served[0], BackgroundImageOptimizer.BLANK_WIDTH);
        assertEquals(served[1], BackgroundImageOptimizer.BLANK_HEIGHT);
        assertFalse(decode(prepared.bytes()).getColorModel().hasAlpha(), "the substitute is opaque white, not a transparent hole");
        assertEquals(prepared.originalWidth(), 400, "the page must not move because the background was replaced");
        assertEquals(prepared.originalHeight(), 300);
    }

    /**
     * A 65535x65535 header is 4.29 billion pixels and 33 bytes of input. It must be rejected from the
     * header alone - the whole point of reading the header before anything is decoded.
     */
    @Test
    public void should_substitute_a_decompression_bomb_it_never_decodes() {
        // given
        byte[] bomb = pngHeaderOnly(65535, 65535);

        // when
        PreparedBackground prepared = optimizer(12_000_000L, 30_000_000L).optimize(bomb, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.SUBSTITUTED);
        assertEquals(prepared.originalWidth(), 65535);
    }

    @Test
    public void should_leave_a_jpeg_above_the_hard_cap_untouched() {
        // given
        byte[] source = jpeg(400, 300);

        // when
        PreparedBackground prepared = optimizer(1_000L, 1_000L).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.UNCHANGED, "OpenPDF embeds JPEG compressed, so capping it costs a background and saves no heap");
        assertSame(prepared.bytes(), source);
    }

    @Test
    public void should_pass_an_unreadable_image_through_unchanged() {
        // given
        byte[] source = "this is not an image".getBytes();

        // when
        PreparedBackground prepared = optimizer(PIXELS - 1, PIXELS - 1).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.UNREADABLE);
        assertSame(prepared.bytes(), source, "an image we cannot judge is one we must not change");
    }

    @Test
    public void should_serve_one_transform_to_every_render_of_the_same_background() {
        // given two equal byte arrays that are not the same object
        BackgroundImageOptimizer optimizer = optimizer(PIXELS - 1, 1_000_000L);
        byte[] first = png(400, 300, false);
        byte[] second = first.clone();

        // when
        PreparedBackground one = optimizer.optimize(first, CORRELATION_ID);
        PreparedBackground two = optimizer.optimize(second, CORRELATION_ID);

        // then
        assertEquals(one.verdict(), Verdict.DOWNSCALED);
        assertSame(two, one, "a second render of the same background must not pay the transform again");
    }

    @Test
    public void should_not_retain_an_image_it_did_not_transform() {
        // given
        BackgroundImageOptimizer optimizer = optimizer(PIXELS + 1, 1_000_000L);
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground one = optimizer.optimize(source, CORRELATION_ID);
        PreparedBackground two = optimizer.optimize(source.clone(), CORRELATION_ID);

        // then
        assertEquals(one.verdict(), Verdict.UNCHANGED);
        assertNotSame(two, one, "caching an untouched image would pin a full-size source for the life of the JVM");
    }

    @Test
    public void should_evict_the_least_recently_used_transform_when_the_byte_budget_is_full() {
        // given a budget with room for either transformed background, but not for both
        byte[] first = png(400, 300, false);
        byte[] second = png(400, 300, true);
        long budget = Math.max(transformedSize(first), transformedSize(second));
        BackgroundImageOptimizer optimizer = optimizerWithBudget(budget);

        // when
        PreparedBackground firstRun = optimizer.optimize(first, CORRELATION_ID);
        PreparedBackground secondRun = optimizer.optimize(second, CORRELATION_ID);
        PreparedBackground secondAgain = optimizer.optimize(second.clone(), CORRELATION_ID);
        PreparedBackground firstAgain = optimizer.optimize(first.clone(), CORRELATION_ID);

        // then
        assertEquals(firstRun.verdict(), Verdict.DOWNSCALED);
        assertSame(secondAgain, secondRun, "the newest transform fits the budget on its own and must be kept");
        assertNotSame(firstAgain, firstRun, "the cache is bounded by bytes, so the eldest transform must be gone");
        assertTrue(optimizer.cachedBytes() <= budget, "held " + optimizer.cachedBytes() + " bytes against a budget of " + budget);
    }

    /**
     * The reason the bound is bytes and not entries: one transform that does not compress can
     * outweigh many that do, so putting it must evict as many entries as its size demands.
     */
    @Test
    public void should_evict_as_many_transforms_as_a_new_one_needs_to_fit() {
        // given two flat backgrounds that compress to almost nothing and one noisy one that does not
        byte[] flatOne = png(400, 300, false);
        byte[] flatTwo = png(400, 300, true);
        byte[] noisy = noisePng(400, 300);
        long flatBytes = transformedSize(flatOne) + transformedSize(flatTwo);
        long noisyBytes = transformedSize(noisy);
        assertTrue(noisyBytes > flatBytes, "fixture premise: the noisy transform (" + noisyBytes + " B) outweighs both flat ones together (" + flatBytes + " B)");
        BackgroundImageOptimizer optimizer = optimizerWithBudget(noisyBytes);

        // when
        PreparedBackground flatOneRun = optimizer.optimize(flatOne, CORRELATION_ID);
        PreparedBackground flatTwoRun = optimizer.optimize(flatTwo, CORRELATION_ID);
        long heldBeforeNoisy = optimizer.cachedBytes();
        PreparedBackground noisyRun = optimizer.optimize(noisy, CORRELATION_ID);
        long heldAfterNoisy = optimizer.cachedBytes();
        PreparedBackground flatOneAgain = optimizer.optimize(flatOne.clone(), CORRELATION_ID);
        PreparedBackground flatTwoAgain = optimizer.optimize(flatTwo.clone(), CORRELATION_ID);

        // then
        assertEquals(heldBeforeNoisy, flatBytes, "both flat transforms fit the budget together");
        assertEquals(noisyRun.verdict(), Verdict.DOWNSCALED);
        assertEquals(heldAfterNoisy, noisyBytes, "the noisy transform must have pushed out both flat ones, not just the eldest");
        assertNotSame(flatOneAgain, flatOneRun, "the first flat transform was evicted to make room");
        assertNotSame(flatTwoAgain, flatTwoRun, "the second flat transform was evicted to make room");
        assertTrue(optimizer.cachedBytes() <= noisyBytes, "the budget holds after every put");
    }

    @Test
    public void should_serve_but_not_keep_a_transform_larger_than_the_whole_budget() {
        // given a budget that holds the flat background and nothing as large as the noisy one
        byte[] flat = png(400, 300, false);
        byte[] noisy = noisePng(400, 300);
        long budget = transformedSize(flat);
        assertTrue(transformedSize(noisy) > budget, "fixture premise: the noisy transform is larger than the whole budget");
        BackgroundImageOptimizer optimizer = optimizerWithBudget(budget);

        // when
        PreparedBackground flatRun = optimizer.optimize(flat, CORRELATION_ID);
        PreparedBackground noisyRun = optimizer.optimize(noisy, CORRELATION_ID);
        PreparedBackground noisyAgain = optimizer.optimize(noisy.clone(), CORRELATION_ID);
        PreparedBackground flatAgain = optimizer.optimize(flat.clone(), CORRELATION_ID);

        // then
        assertEquals(noisyRun.verdict(), Verdict.DOWNSCALED, "a result too large to cache is still served");
        assertNotSame(noisyAgain, noisyRun, "a result larger than the whole budget must not be stored");
        assertSame(flatAgain, flatRun, "an oversized result must not evict the entries that do fit: it would empty the cache and buy back nothing");
        assertEquals(optimizer.cachedBytes(), budget);
    }

    /**
     * Guards the two defaults against drifting apart: a downscale at {@code targetPixels} is a PNG of
     * at most about four bytes per pixel, and the default budget must hold at least one of them, or
     * a background that does not compress would be re-decoded on every render.
     */
    @Test
    public void should_default_to_a_budget_that_holds_at_least_one_worst_case_downscale() {
        long worstCaseDownscale = BackgroundImageOptimizer.DEFAULT_TARGET_PIXELS * 4 * 105 / 100;
        assertTrue(BackgroundImageOptimizer.DEFAULT_MAX_CACHE_BYTES >= worstCaseDownscale,
                BackgroundImageOptimizer.DEFAULT_MAX_CACHE_BYTES + " B budget vs " + worstCaseDownscale + " B worst-case downscale");
    }

    @Test
    public void should_hand_every_concurrent_render_of_one_cold_background_the_same_result() throws Exception {
        // given
        int threads = 12;
        BackgroundImageOptimizer optimizer = optimizer(PIXELS - 1, 1_000_000L);
        byte[] source = png(400, 300, false);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<PreparedBackground> results = Collections.synchronizedList(new ArrayList<>());

        // when
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    results.add(optimizer.optimize(source.clone(), CORRELATION_ID));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "every render thread must finish");

        // then
        assertEquals(results.size(), threads);
        Set<PreparedBackground> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(results);
        assertEquals(distinct.size(), 1, threads + " concurrent cold renders must pay one transform, not " + distinct.size());
    }

    @Test
    public void should_degrade_rather_than_throw_on_a_hostile_cache_budget() {
        // given
        byte[] source = png(400, 300, false);

        // when
        Map<Long, Verdict> verdicts = new java.util.LinkedHashMap<>();
        for (long maxCacheBytes : new long[]{0L, -5L}) {
            BackgroundImageOptimizer optimizer = optimizerWithBudget(maxCacheBytes);
            verdicts.put(maxCacheBytes, optimizer.optimize(source, CORRELATION_ID).verdict());
            assertEquals(optimizer.cachedBytes(), 0L, "maxCacheBytes=" + maxCacheBytes + " must cache nothing");
        }

        // then
        assertEquals(verdicts.size(), 2, "both hostile values must have been exercised");
        verdicts.forEach((maxCacheBytes, verdict) ->
                assertEquals(verdict, Verdict.DOWNSCALED, "maxCacheBytes=" + maxCacheBytes + " must still render"));
    }

    @Test
    public void should_report_itself_disabled_when_the_feature_flag_is_off() {
        assertFalse(new BackgroundImageOptimizer(false, PIXELS - 1, TARGET, 1_000_000L, BackgroundImageOptimizer.DEFAULT_MAX_CACHE_BYTES).isEnabled());
        assertTrue(optimizer(PIXELS - 1, 1_000_000L).isEnabled());
    }

}
