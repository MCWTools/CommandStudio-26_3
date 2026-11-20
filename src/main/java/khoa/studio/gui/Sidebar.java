package khoa.studio.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import khoa.studio.form.Forms;

/** The generator list shared by every Command Studio screen (scrolls when the window is short). The open generator is highlighted. */
public final class Sidebar {
	static final int WIDTH = 110;
	private static final int ROW = 16;
	private static final int TOP = 26;

	private record Entry(String id, String label) {
	}

	private static final Entry[] ENTRIES = build();
	private static int scroll;

	private Sidebar() {
	}

	private static Entry[] build() {
		java.util.List<Entry> list = new java.util.ArrayList<>();
		list.add(new Entry("give", "Give Generator"));
		list.add(new Entry("entity", "Entity Generator"));
		Forms.all().forEach(def -> list.add(new Entry(def.id(), def.label())));
		return list.toArray(new Entry[0]);
	}

	public static String labelOf(String id) {
		for (Entry e : ENTRIES) {
			if (e.id().equals(id)) {
				return e.label();
			}
		}
		return id;
	}

	public static int count() {
		return ENTRIES.length;
	}

	public static String idAt(int index) {
		return ENTRIES[index].id();
	}

	private static int visibleRows(int height) {
		return Math.max(1, (height - 8 - TOP - 2) / ROW);
	}

	private static void clamp(int height) {
		scroll = Math.max(0, Math.min(scroll, Math.max(0, ENTRIES.length - visibleRows(height))));
	}

	static void render(GuiGraphicsExtractor g, Font font, int x, int height, String current, int mouseX, int mouseY) {
		clamp(height);
		g.fill(x, 22, x + WIDTH, height - 8, 0xA0101010);
		int rows = visibleRows(height);
		for (int n = 0; n < rows && scroll + n < ENTRIES.length; n++) {
			int i = scroll + n;
			int y = TOP + n * ROW;
			Entry e = ENTRIES[i];
			boolean active = e.id().equals(current);
			boolean hover = mouseX >= x && mouseX < x + WIDTH && mouseY >= y - 2 && mouseY < y + ROW - 2;
			if (active) {
				g.fill(x + 2, y - 2, x + WIDTH - 2, y + ROW - 2, 0xA02E6E9E);
			} else if (hover) {
				g.fill(x + 2, y - 2, x + WIDTH - 2, y + ROW - 2, 0x80404040);
			}
			g.text(font, font.plainSubstrByWidth(e.label().replace(" Generator", ""), WIDTH - 12), x + 5, y + 1, 0xFFFFFFFF);
		}
		if (ENTRIES.length > rows) {
			int trackH = height - 8 - 22 - 4;
			int barH = Math.max(10, trackH * rows / ENTRIES.length);
			int barY = 24 + (trackH - barH) * scroll / Math.max(1, ENTRIES.length - rows);
			g.fill(x + WIDTH - 3, barY, x + WIDTH - 1, barY + barH, 0xFF888888);
		}
	}

	/** Mouse wheel over the list; returns true when it was over the list. */
	static boolean scrolled(double mx, double my, int x, int height, double sy) {
		if (mx < x || mx >= x + WIDTH || my < 22 || my >= height - 8) {
			return false;
		}
		scroll -= (int) Math.signum(sy);
		clamp(height);
		return true;
	}

	/** Returns true when the click hit a row; opens that generator unless it is the current one. */
	static boolean click(double mx, double my, int x, int height, String current) {
		if (mx < x || mx >= x + WIDTH) {
			return false;
		}
		clamp(height);
		int n = (int) Math.floor((my - (TOP - 2)) / ROW);
		int i = scroll + n;
		if (n >= 0 && n < visibleRows(height) && i < ENTRIES.length) {
			if (!ENTRIES[i].id().equals(current)) {
				openById(ENTRIES[i].id());
			}
			return true;
		}
		return false;
	}

	/** Position of a row's centre for the automated tests (scrolls the list so the row is visible). */
	public static double[] rowCenter(String id, int x, int height) {
		for (int i = 0; i < ENTRIES.length; i++) {
			if (ENTRIES[i].id().equals(id)) {
				int rows = visibleRows(height);
				if (i < scroll || i >= scroll + rows) {
					scroll = Math.min(i, Math.max(0, ENTRIES.length - rows));
				}
				return new double[] { x + WIDTH / 2.0, TOP + (i - scroll) * ROW + ROW / 2.0 - 2 };
			}
		}
		return null;
	}

	public static void openById(String id) {
		if (id.equals("give")) {
			CommandStudioScreen.open();
		} else if (id.equals("entity")) {
			EntityGeneratorScreen.open();
		} else if (Forms.get(id) != null) {
			FormGeneratorScreen.open(id);
		} else {
			CommandStudioScreen.open();
		}
	}

	/** Small-window replacement for the list: opens the next generator in the list. */
	static void openNext(String current) {
		for (int i = 0; i < ENTRIES.length; i++) {
			if (ENTRIES[i].id().equals(current)) {
				openById(ENTRIES[(i + 1) % ENTRIES.length].id());
				return;
			}
		}
		openById("give");
	}

	static String otherLabel(String current) {
		for (int i = 0; i < ENTRIES.length; i++) {
			if (ENTRIES[i].id().equals(current)) {
				return ENTRIES[(i + 1) % ENTRIES.length].label().replace(" Generator", "") + " >";
			}
		}
		return "Give >";
	}
}
