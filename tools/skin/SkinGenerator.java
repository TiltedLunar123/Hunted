import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Draws the hunter's texture.
 *
 * <p>The skin is generated rather than hand painted so it lives in the repo as
 * something you can read and change, instead of a binary nobody dares touch. Run
 * it with a modern JDK and no build step:
 *
 * <pre>
 *   java tools/skin/SkinGenerator.java mod/src/main/resources/assets/hunted/textures/entity/hunter.png
 * </pre>
 *
 * <p>The design has to survive being about twelve pixels tall on screen, so it
 * leans on three things that stay readable at that size: a very dark body, a
 * bright chrome skull, and two red eyes with nothing else competing for
 * attention. Detail that only reads in a texture viewer is wasted here.
 *
 * <p>Layout is the standard 64 by 64 humanoid sheet, same as a player skin.
 */
public final class SkinGenerator {

	private static final int SIZE = 64;

	// Palette. Deliberately narrow: a machine should not look colourful.
	private static final int VOID = 0x00000000;
	private static final int BLACK = 0xFF0E1013;
	private static final int STEEL_DARK = 0xFF1C2126;
	private static final int STEEL = 0xFF2C333A;
	private static final int STEEL_LIT = 0xFF3E474F;
	private static final int CHROME_DARK = 0xFF6E7A86;
	private static final int CHROME = 0xFF97A4B0;
	private static final int CHROME_LIT = 0xFFC2CEDA;
	private static final int EYE = 0xFFFF2A18;
	private static final int EYE_HOT = 0xFFFF8C6E;
	private static final int EMBER = 0xFF7E1408;

	private final BufferedImage image =
			new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);

	public static void main(String[] args) throws IOException {
		Path out = Path.of(args.length > 0
				? args[0]
				: "mod/src/main/resources/assets/hunted/textures/entity/hunter.png");

		SkinGenerator generator = new SkinGenerator();
		generator.draw();

		File file = out.toFile();
		if (file.getParentFile() != null) {
			Files.createDirectories(file.getParentFile().toPath());
		}
		ImageIO.write(generator.image, "PNG", file);
		System.out.println("Wrote " + out.toAbsolutePath());
	}

	private void draw() {
		clear();
		head();
		body();
		rightArm();
		leftArm();
		rightLeg();
		leftLeg();
	}

	private void clear() {
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				image.setRGB(x, y, VOID);
			}
		}
	}

	// -----------------------------------------------------------------
	// Head. Chrome skull, dark sockets, two red eyes.
	// -----------------------------------------------------------------

	private void head() {
		// Faces: right, front, left, back at y 8, plus top and bottom at y 0.
		plate(0, 8, 8, 8, CHROME_DARK, CHROME);
		plate(8, 8, 8, 8, CHROME, CHROME_LIT);
		plate(16, 8, 8, 8, CHROME_DARK, CHROME);
		plate(24, 8, 8, 8, STEEL, STEEL_LIT);
		plate(8, 0, 8, 8, CHROME, CHROME_LIT);
		plate(16, 0, 8, 8, STEEL_DARK, STEEL);

		// Brow ridge across the front.
		rect(8, 10, 8, 1, CHROME_LIT);

		// Sockets, then the eyes inside them.
		rect(9, 11, 2, 2, BLACK);
		rect(13, 11, 2, 2, BLACK);
		rect(9, 11, 2, 1, EYE);
		rect(13, 11, 2, 1, EYE);
		pixel(10, 11, EYE_HOT);
		pixel(13, 11, EYE_HOT);

		// Jaw grille.
		rect(9, 14, 6, 1, STEEL_DARK);
		pixel(10, 14, BLACK);
		pixel(12, 14, BLACK);
		pixel(14, 14, BLACK);

		// Cheek seams, so the front is not a flat slab.
		rect(8, 13, 1, 2, CHROME_DARK);
		rect(15, 13, 1, 2, CHROME_DARK);

		// Side vents.
		rect(2, 11, 4, 1, STEEL_DARK);
		rect(2, 13, 3, 1, STEEL_DARK);
		rect(18, 11, 4, 1, STEEL_DARK);
		rect(19, 13, 3, 1, STEEL_DARK);

		// Back skull seam with a hint of the reactor behind it.
		rect(27, 9, 2, 6, STEEL_DARK);
		rect(28, 11, 1, 2, EMBER);
	}

	// -----------------------------------------------------------------
	// Body. Dark armour with an exposed core.
	// -----------------------------------------------------------------

	private void body() {
		plate(16, 20, 4, 12, STEEL_DARK, STEEL);   // right side
		plate(20, 20, 8, 12, STEEL, STEEL_LIT);    // front
		plate(28, 20, 4, 12, STEEL_DARK, STEEL);   // left side
		plate(32, 20, 8, 12, STEEL_DARK, STEEL);   // back
		plate(20, 16, 8, 4, STEEL_LIT, CHROME_DARK); // top
		plate(28, 16, 8, 4, STEEL_DARK, STEEL);      // bottom

		// Collarbone plating.
		rect(20, 20, 8, 1, CHROME_DARK);
		rect(21, 21, 6, 1, STEEL_LIT);

		// The core. One warm thing on an otherwise cold model.
		rect(23, 23, 2, 3, EMBER);
		rect(23, 24, 2, 1, EYE);
		pixel(23, 24, EYE_HOT);

		// Rib seams either side of the core.
		rect(21, 23, 1, 5, STEEL_DARK);
		rect(26, 23, 1, 5, STEEL_DARK);

		// Abdominal segments.
		for (int y = 27; y < 31; y += 2) {
			rect(21, y, 6, 1, STEEL_DARK);
		}

		// Spine down the back.
		rect(35, 21, 2, 10, STEEL);
		for (int y = 22; y < 31; y += 2) {
			rect(35, y, 2, 1, CHROME_DARK);
		}
	}

	// -----------------------------------------------------------------
	// Limbs. Steel with a chrome joint band so movement reads at distance.
	// -----------------------------------------------------------------

	private void rightArm() {
		limb(40, 20, 44, 16);
	}

	private void leftArm() {
		limb(32, 52, 36, 48);
	}

	private void rightLeg() {
		limb(0, 20, 4, 16);
	}

	private void leftLeg() {
		limb(16, 52, 20, 48);
	}

	/**
	 * Draws one four by twelve limb.
	 *
	 * @param x    left edge of the four side faces
	 * @param y    top edge of the side faces
	 * @param capX left edge of the top and bottom caps
	 * @param capY top edge of the caps
	 */
	private void limb(int x, int y, int capX, int capY) {
		plate(x, y, 4, 12, STEEL_DARK, STEEL);
		plate(x + 4, y, 4, 12, STEEL, STEEL_LIT);
		plate(x + 8, y, 4, 12, STEEL_DARK, STEEL);
		plate(x + 12, y, 4, 12, STEEL_DARK, STEEL);
		plate(capX, capY, 4, 4, STEEL_LIT, CHROME_DARK);
		plate(capX + 4, capY, 4, 4, STEEL_DARK, STEEL);

		// Joint band at the midpoint, across all four faces.
		rect(x, y + 5, 16, 1, CHROME_DARK);
		rect(x, y + 6, 16, 1, STEEL_DARK);

		// Hydraulic line on the front face.
		rect(x + 5, y + 1, 1, 4, CHROME_DARK);
		rect(x + 5, y + 7, 1, 4, CHROME_DARK);

		// Hard shadow along the bottom.
		rect(x, y + 11, 16, 1, BLACK);
	}

	// -----------------------------------------------------------------
	// Drawing helpers
	// -----------------------------------------------------------------

	/** Fills a rectangle, then lights the top edge and darkens the bottom. */
	private void plate(int x, int y, int w, int h, int base, int highlight) {
		rect(x, y, w, h, base);
		rect(x, y, w, 1, highlight);
		rect(x, y + h - 1, w, 1, shade(base));
	}

	private void rect(int x, int y, int w, int h, int argb) {
		for (int dy = 0; dy < h; dy++) {
			for (int dx = 0; dx < w; dx++) {
				pixel(x + dx, y + dy, argb);
			}
		}
	}

	private void pixel(int x, int y, int argb) {
		if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) {
			image.setRGB(x, y, argb);
		}
	}

	/** A darker version of a colour, for the shadowed edge of a plate. */
	private static int shade(int argb) {
		int a = argb >>> 24;
		int r = (int) (((argb >> 16) & 0xFF) * 0.62);
		int g = (int) (((argb >> 8) & 0xFF) * 0.62);
		int b = (int) ((argb & 0xFF) * 0.62);
		return a << 24 | r << 16 | g << 8 | b;
	}
}
