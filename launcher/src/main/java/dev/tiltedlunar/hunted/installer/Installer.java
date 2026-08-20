package dev.tiltedlunar.hunted.installer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

/**
 * The installer window.
 *
 * <p>One screen, one button. The only decision a player is asked to make is
 * which Minecraft version, and that is pre-filled with the right answer. Every
 * other thing an installer usually asks about, where the game lives, which
 * loader, which API version, where the mods folder is, is either detected or
 * decided here, because none of those are questions a person wanting to play a
 * game should have to answer.
 *
 * <p>Also runs headless. Pass {@code --minecraft <path>} to install from a
 * script or on a server with no display.
 */
public final class Installer {

	private static final Color BACKGROUND = new Color(0x1C2126);
	private static final Color PANEL = new Color(0x252C33);
	private static final Color TEXT = new Color(0xC2CEDA);
	private static final Color MUTED = new Color(0x7E8B98);
	private static final Color ACCENT = new Color(0xE23B25);
	private static final Color ACCENT_DIM = new Color(0x8E1208);

	private final Installation installation = new Installation();

	private JTextField pathField;
	private JComboBox<String> versionBox;
	private JButton installButton;
	private JProgressBar progressBar;
	private JLabel statusLabel;

	public static void main(String[] args) {
		if (args.length > 0 || java.awt.GraphicsEnvironment.isHeadless()) {
			System.exit(runHeadless(args));
			return;
		}
		SwingUtilities.invokeLater(() -> new Installer().show());
	}

	// -----------------------------------------------------------------
	// Command line
	// -----------------------------------------------------------------

	private static int runHeadless(String[] args) {
		Path minecraft = null;
		String version = Bundled.minecraftVersion();

		for (int i = 0; i < args.length - 1; i++) {
			if ("--minecraft".equals(args[i])) {
				minecraft = Paths.get(args[i + 1]);
			} else if ("--version".equals(args[i])) {
				version = args[i + 1];
			}
		}
		if (minecraft == null) {
			minecraft = MinecraftPaths.detect();
		}
		if (minecraft == null) {
			System.err.println("Could not find Minecraft. Pass --minecraft <path>.");
			return 2;
		}

		System.out.println("Installing Hunted " + Bundled.modVersion()
				+ " for Minecraft " + version + " into " + minecraft);
		try {
			String id = new Installation().install(minecraft, version, Bundled.loaderVersion(),
					new Installation.Progress() {
						@Override
						public void step(String message) {
							System.out.println("  " + message);
						}

						@Override
						public void percent(int value) {
							// Nothing to draw on a terminal.
						}
					});
			System.out.println("Done. Open the Minecraft launcher and pick the Hunted profile.");
			System.out.println("Version id: " + id);
			return 0;
		} catch (Installation.InstallException e) {
			System.err.println("Install failed: " + e.getMessage());
			return 1;
		}
	}

	// -----------------------------------------------------------------
	// Window
	// -----------------------------------------------------------------

	private void show() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception ignored) {
			// The default look and feel is fine.
		}

		JFrame frame = new JFrame("Hunted Installer");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setResizable(false);
		frame.setContentPane(createRoot());
		frame.pack();
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	/**
	 * Builds the whole window contents.
	 *
	 * <p>Separate from {@link #show()} so the documentation screenshot can be
	 * rendered from the real components rather than mocked up by hand.
	 */
	JPanel createRoot() {
		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(BACKGROUND);
		root.add(header(), BorderLayout.NORTH);
		root.add(body(), BorderLayout.CENTER);
		root.add(footer(), BorderLayout.SOUTH);
		return root;
	}

	/** Brand bar. The eyes are the only red on screen until something goes wrong. */
	private JPanel header() {
		JPanel panel = new JPanel(new BorderLayout()) {
			@Override
			protected void paintComponent(Graphics g) {
				super.paintComponent(g);
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setPaint(new GradientPaint(0, 0, new Color(0x141A1F),
						getWidth(), getHeight(), new Color(0x232B33)));
				g2.fillRect(0, 0, getWidth(), getHeight());

				// Two red eyes, drawn rather than shipped as an image.
				int cy = getHeight() / 2;
				g2.setColor(ACCENT_DIM);
				g2.fillRect(26, cy - 7, 14, 14);
				g2.fillRect(46, cy - 7, 14, 14);
				g2.setColor(ACCENT);
				g2.fillRect(28, cy - 4, 10, 5);
				g2.fillRect(48, cy - 4, 10, 5);
				g2.dispose();
			}
		};
		panel.setPreferredSize(new Dimension(620, 80));
		panel.setBorder(new EmptyBorder(0, 78, 0, 24));

		// A GridLayout rather than a BoxLayout on purpose. BoxLayout gives each
		// child exactly its preferred width, and Swing under-measures derived
		// fonts by a pixel or two on Windows, which clips the last character.
		JPanel text = new JPanel(new java.awt.GridLayout(2, 1, 0, 2));
		text.setOpaque(false);
		text.setBorder(new EmptyBorder(16, 0, 16, 0));

		JLabel title = new JLabel("HUNTED");
		title.setForeground(TEXT);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
		title.setVerticalAlignment(JLabel.BOTTOM);

		JLabel subtitle = new JLabel("It learns the terrain. Then it comes for you.");
		subtitle.setForeground(MUTED);
		subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
		subtitle.setVerticalAlignment(JLabel.TOP);

		text.add(title);
		text.add(subtitle);

		panel.add(text, BorderLayout.CENTER);
		return panel;
	}

	private JPanel body() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(BACKGROUND);
		panel.setBorder(new EmptyBorder(22, 24, 8, 24));

		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(6, 0, 6, 8);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;

		Path detected = MinecraftPaths.detect();

		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 0;
		panel.add(label("Minecraft folder"), c);

		pathField = new JTextField(detected == null ? "" : detected.toString(), 28);
		style(pathField);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(pathField, c);

		JButton browse = new JButton("Browse");
		browse.setFocusPainted(false);
		browse.addActionListener(e -> chooseFolder());
		c.gridx = 2;
		c.weightx = 0;
		panel.add(browse, c);

		c.gridx = 0;
		c.gridy = 1;
		panel.add(label("Minecraft version"), c);

		versionBox = new JComboBox<>(Bundled.supportedMinecraftVersions());
		versionBox.setSelectedItem(Bundled.minecraftVersion());
		style(versionBox);
		c.gridx = 1;
		c.gridwidth = 2;
		panel.add(versionBox, c);

		c.gridx = 0;
		c.gridy = 2;
		c.gridwidth = 3;
		c.insets = new Insets(14, 0, 4, 0);
		progressBar = new JProgressBar(0, 100);
		progressBar.setForeground(ACCENT);
		progressBar.setBackground(PANEL);
		progressBar.setBorderPainted(false);
		progressBar.setVisible(false);
		panel.add(progressBar, c);

		c.gridy = 3;
		c.insets = new Insets(0, 0, 0, 0);
		statusLabel = new JLabel(detected == null
				? "Could not find Minecraft. Point at the folder yourself."
				: "Ready to install for Minecraft " + Bundled.minecraftVersion() + ".");
		statusLabel.setForeground(MUTED);
		statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));
		panel.add(statusLabel, c);

		return panel;
	}

	private JPanel footer() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(BACKGROUND);
		panel.setBorder(new EmptyBorder(6, 24, 20, 24));

		installButton = new JButton("Install") {
			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(isEnabled() ? ACCENT : new Color(0x4A5259));
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		installButton.setContentAreaFilled(false);
		installButton.setBorderPainted(false);
		installButton.setFocusPainted(false);
		installButton.setForeground(Color.WHITE);
		installButton.setFont(installButton.getFont().deriveFont(Font.BOLD, 15f));
		installButton.setPreferredSize(new Dimension(160, 40));
		installButton.addActionListener(e -> startInstall());

		JLabel version = new JLabel("v" + Bundled.modVersion());
		version.setForeground(new Color(0x4E5A66));
		version.setFont(version.getFont().deriveFont(Font.PLAIN, 11f));
		// Slack on the right for the same under-measure reason as the header.
		version.setBorder(new EmptyBorder(0, 0, 0, 12));

		panel.add(version, BorderLayout.WEST);
		panel.add(installButton, BorderLayout.EAST);
		return panel;
	}

	// -----------------------------------------------------------------

	private void chooseFolder() {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Where is Minecraft?");
		String current = pathField.getText();
		if (!current.isBlank()) {
			chooser.setCurrentDirectory(Paths.get(current).toFile());
		}
		if (chooser.showOpenDialog(pathField) == JFileChooser.APPROVE_OPTION) {
			pathField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void startInstall() {
		String raw = pathField.getText().trim();
		if (raw.isEmpty()) {
			fail("Pick your Minecraft folder first.");
			return;
		}

		Path minecraft = Paths.get(raw);
		String version = String.valueOf(versionBox.getSelectedItem());

		installButton.setEnabled(false);
		progressBar.setVisible(true);
		progressBar.setValue(0);
		setStatus("Starting", MUTED);

		new SwingWorker<String, Object[]>() {
			@Override
			protected String doInBackground() throws Exception {
				return installation.install(minecraft, version, Bundled.loaderVersion(),
						new Installation.Progress() {
							@Override
							public void step(String message) {
								publish(new Object[]{"step", message});
							}

							@Override
							public void percent(int value) {
								publish(new Object[]{"percent", value});
							}
						});
			}

			@Override
			protected void process(java.util.List<Object[]> chunks) {
				for (Object[] chunk : chunks) {
					if ("step".equals(chunk[0])) {
						setStatus(String.valueOf(chunk[1]), MUTED);
					} else {
						progressBar.setValue((Integer) chunk[1]);
					}
				}
			}

			@Override
			protected void done() {
				installButton.setEnabled(true);
				try {
					get();
					setStatus("Installed. Open the Minecraft launcher and pick Hunted.", ACCENT);
					installButton.setText("Install again");
				} catch (Exception e) {
					Throwable cause = e.getCause() == null ? e : e.getCause();
					fail(cause.getMessage() == null ? cause.toString() : cause.getMessage());
				}
			}
		}.execute();
	}

	private void fail(String message) {
		progressBar.setVisible(false);
		setStatus(message, ACCENT);
	}

	private void setStatus(String message, Color colour) {
		statusLabel.setForeground(colour);
		statusLabel.setText(message);
	}

	private JLabel label(String text) {
		JLabel label = new JLabel(text);
		label.setForeground(TEXT);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, 13f));
		return label;
	}

	private void style(javax.swing.JComponent component) {
		component.setBackground(PANEL);
		component.setForeground(TEXT);
		component.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(new Color(0x39424B)),
				new EmptyBorder(6, 8, 6, 8)));
		if (component instanceof JTextField field) {
			field.setCaretColor(TEXT);
		}
	}

	/** Used by the tests to check the supported version list is sane. */
	static String[] supportedVersions() {
		return Arrays.stream(Bundled.supportedMinecraftVersions())
				.map(String::trim)
				.filter(v -> !v.isEmpty())
				.toArray(String[]::new);
	}
}
