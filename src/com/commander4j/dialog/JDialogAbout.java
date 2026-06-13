package com.commander4j.dialog;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import com.commander4j.client.JUtility;
import com.commander4j.gui.JButton4j;
import com.commander4j.gui.JLabel4j_std;
import com.commander4j.sys.Common;

public class JDialogAbout extends JDialog
{

	private static final long serialVersionUID = 1L;
	private final JPanel contentPanel = new JPanel();
	private static int widthadjustment = 0;
	private static int heightadjustment = 0;
	private JLabel4j_std jLabelWebPage;

	public JDialogAbout()
	{
		setResizable(false);
		setTitle("About");
		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		setModalityType(ModalityType.APPLICATION_MODAL);
		System.setProperty("apple.laf.useScreenMenuBar", "true");
		JUtility.setLookAndFeel("Nimbus");

		setBounds(100, 100, 400, 330);
		getContentPane().setLayout(null);
		contentPanel.setBackground(Common.color_app_window);
		contentPanel.setBounds(0, 0, getWidth(), getHeight());
		contentPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
		getContentPane().add(contentPanel);
		contentPanel.setLayout(null);

		JLabel4j_std lbl_description = new JLabel4j_std("Description");
		lbl_description.setFont(new Font("Arial", Font.ITALIC, 13));
		lbl_description.setText("David Garratt");
		lbl_description.setHorizontalAlignment(SwingConstants.CENTER);
		lbl_description.setBounds(6, 43, 374, 22);
		contentPanel.add(lbl_description);

		JLabel4j_std lbl_disclaimer = new JLabel4j_std();
		lbl_disclaimer.setFont(new Font("Arial", Font.PLAIN, 11));
		lbl_disclaimer.setText("<html><div style='text-align:center;'>" + Common.disclaimer + "</div></html>");
		lbl_disclaimer.setVerticalAlignment(SwingConstants.TOP);
		lbl_disclaimer.setBounds(16, 100, 354, 130);
		contentPanel.add(lbl_disclaimer);

		{
			JButton4j okButton = new JButton4j(Common.icon_ok);
			okButton.setText("Ok");
			okButton.addActionListener(new ActionListener()
			{
				public void actionPerformed(ActionEvent e)
				{
					dispose();
				}
			});
			okButton.setBounds(142, 245, 103, 30);
			contentPanel.add(okButton);
			okButton.setActionCommand("OK");
			getRootPane().setDefaultButton(okButton);
		}

		{
			jLabelWebPage = new JLabel4j_std();
			jLabelWebPage.setFont(new Font("Arial", Font.PLAIN, 12));
			jLabelWebPage.setHorizontalAlignment(SwingConstants.CENTER);
			contentPanel.add(jLabelWebPage);
			jLabelWebPage.setText("https://www.commander4j.com");
			jLabelWebPage.setBounds(6, 77, 374, 14);
			jLabelWebPage.setForeground(new Color(0, 0, 255));
			jLabelWebPage.addMouseListener(new MouseAdapter()
			{
				public void mouseClicked(MouseEvent evt)
				{
					try
					{
						if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
						{
							Desktop.getDesktop().browse(new URI("http://www.commander4j.com"));
						}
					}
					catch (Exception ex)
					{
						JOptionPane.showMessageDialog(JDialogAbout.this, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
					}
				}

				public void mouseExited(MouseEvent evt)
				{
					Cursor normalCursor = new Cursor(Cursor.DEFAULT_CURSOR);
					setCursor(normalCursor);
				}

				public void mouseEntered(MouseEvent evt)
				{
					Cursor hourglassCursor = new Cursor(Cursor.HAND_CURSOR);
					setCursor(hourglassCursor);
				}
			});
		}

		widthadjustment = JUtility.getOSWidthAdjustment();
		heightadjustment = JUtility.getOSHeightAdjustment();

		GraphicsDevice gd = JUtility.getGraphicsDevice();

		GraphicsConfiguration gc = gd.getDefaultConfiguration();

		Rectangle screenBounds = gc.getBounds();

		setBounds(screenBounds.x + ((screenBounds.width - JDialogAbout.this.getWidth()) / 2), screenBounds.y + ((screenBounds.height - JDialogAbout.this.getHeight()) / 2), JDialogAbout.this.getWidth() + widthadjustment, JDialogAbout.this.getHeight() + heightadjustment);

		SwingUtilities.invokeLater(new Runnable()
		{
			public void run()
			{
				JLabel4j_std lbl_type = new JLabel4j_std("Type");
				lbl_type.setFont(new Font("Arial", Font.ITALIC, 13));
				lbl_type.setText("Written by");
				lbl_type.setBounds(6, 11, 374, 22);
				contentPanel.add(lbl_type);
				lbl_type.setHorizontalAlignment(SwingConstants.CENTER);
			}
		});
	}
}
