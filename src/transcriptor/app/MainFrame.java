package transcriptor.app;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

public final class MainFrame extends JFrame {
    private static final DecimalFormat SECONDS_FORMAT = new DecimalFormat("0.0");
    private static final String[] MEDIA_EXTENSIONS = {
        "mp4", "mov", "mkv", "avi", "wmv", "webm", "mp3", "wav", "m4a", "flac", "aac", "ogg"
    };

    private final Path appRoot = Path.of(System.getProperty("app.root", ".")).toAbsolutePath().normalize();
    private final Path defaultOutputDirectory = appRoot.resolve("outputs");
    private final Path modelCacheDirectory = appRoot.resolve("cache").resolve("models");

    private final DefaultListModel<TranscriptionJob> jobListModel = new DefaultListModel<>();
    private final JList<TranscriptionJob> jobList = new JList<>(jobListModel);
    private final JTextField outputDirectoryField = new JTextField(defaultOutputDirectory.toString());
    private final JComboBox<String> languageCombo = new JComboBox<>(new String[]{
        "Auto detectar", "Español", "English"
    });
    private final JComboBox<String> modelCombo = new JComboBox<>(new String[]{
        "tiny", "base", "small", "medium", "large-v3"
    });
    private final JComboBox<String> deviceCombo = new JComboBox<>(new String[]{
        "Auto (GPU si existe)", "Solo GPU NVIDIA", "Solo CPU"
    });
    private final JButton addFilesButton = createButton("Agregar archivos", true);
    private final JButton removeFilesButton = createButton("Quitar seleccionados", false);
    private final JButton clearQueueButton = createButton("Vaciar cola", false);
    private final JButton pickOutputButton = createButton("Cambiar carpeta", false);
    private final JButton openOutputButton = createButton("Abrir carpeta", false);
    private final JButton startButton = createButton("Iniciar transcripción", true);
    private final JButton stopButton = createButton("Detener", false);
    private final JTextArea transcriptArea = createTextArea(false);
    private final JTextArea logArea = createTextArea(false);
    private final JLabel statusBadge = new JLabel("Listo para preparar una cola");
    private final JLabel engineBadge = new JLabel("Motor pendiente");
    private final JLabel queueBadge = new JLabel("0 archivos");
    private final JLabel summaryLabel = new JLabel("Arrastra videos, elige modelo y transcribe sin depender de la nube.");
    private final JProgressBar progressBar = new JProgressBar(0, 100);

    private final TranscriptionEngine engine = new TranscriptionEngine(appRoot);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private volatile boolean runningQueue;
    private volatile TranscriptionJob activeJob;

    public MainFrame() {
        setTitle("Transcriptor Local");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int width = Math.min(1420, Math.max(1120, screen.width - 120));
        int height = Math.min(880, Math.max(700, screen.height - 120));
        setMinimumSize(new Dimension(1040, 680));
        setSize(width, height);
        setLocationRelativeTo(null);

        outputDirectoryField.setEditable(false);
        outputDirectoryField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AppTheme.BORDER, 1, true),
            new EmptyBorder(10, 12, 10, 12)
        ));

        logArea.setRows(8);
        transcriptArea.setRows(18);

        languageCombo.setSelectedIndex(0);
        modelCombo.setSelectedItem("small");
        deviceCombo.setSelectedIndex(0);
        progressBar.setStringPainted(true);
        progressBar.setString("Esperando trabajo");

        configureList();
        configureActions();
        buildUi();
        ensureDirectories();
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(defaultOutputDirectory);
            Files.createDirectories(modelCacheDirectory);
        } catch (IOException exception) {
            appendLog("No se pudieron preparar las carpetas locales: " + exception.getMessage());
        }
    }

    private void configureList() {
        jobList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jobList.setCellRenderer(new JobRenderer());
        jobList.setFixedCellHeight(72);
        jobList.setBackground(AppTheme.PANEL);
        jobList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showSelectedJobTranscript();
            }
        });
        jobList.setTransferHandler(new FileDropHandler());
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(18, 18));
        root.setBorder(new EmptyBorder(18, 18, 18, 18));
        root.setBackground(AppTheme.BACKGROUND);

        root.add(createHeroPanel(), BorderLayout.NORTH);
        root.add(createCenterPane(), BorderLayout.CENTER);
        root.add(createFooterPanel(), BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JComponent createHeroPanel() {
        GradientPanel hero = new GradientPanel();
        hero.setLayout(new BorderLayout(18, 0));
        hero.setBorder(new EmptyBorder(22, 24, 22, 24));

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Transcriptor Local CUDA / CPU");
        title.setForeground(Color.WHITE);
        title.setFont(AppTheme.pickFont(28f, Font.BOLD));
        summaryLabel.setForeground(new Color(235, 243, 239));
        summaryLabel.setFont(AppTheme.pickFont(15f, Font.PLAIN));

        copy.add(title);
        copy.add(Box.createVerticalStrut(10));
        copy.add(summaryLabel);

        JPanel metrics = new JPanel(new GridBagLayout());
        metrics.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        metrics.add(metricCard("Estado", statusBadge), gbc);
        metrics.add(metricCard("Motor", engineBadge), gbc);
        metrics.add(metricCard("Cola", queueBadge), gbc);

        hero.add(copy, BorderLayout.CENTER);
        hero.add(metrics, BorderLayout.EAST);
        return hero;
    }

    private JComponent createCenterPane() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createLeftColumn(), createRightColumn());
        splitPane.setDividerLocation(430);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setOpaque(false);
        return splitPane;
    }

    private JComponent createLeftColumn() {
        JScrollPane optionsScroll = new JScrollPane(createOptionsCard());
        optionsScroll.setBorder(BorderFactory.createEmptyBorder());
        optionsScroll.getViewport().setBackground(AppTheme.BACKGROUND);
        optionsScroll.getVerticalScrollBar().setUnitIncrement(16);
        optionsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, createQueueCard(), optionsScroll);
        splitPane.setDividerLocation(340);
        splitPane.setResizeWeight(0.55);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setOpaque(false);
        return splitPane;
    }

    private JComponent createQueueCard() {
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setOpaque(false);

        JScrollPane listScroll = new JScrollPane(jobList);
        listScroll.setBorder(BorderFactory.createLineBorder(AppTheme.BORDER, 1, true));
        listScroll.getViewport().setBackground(AppTheme.PANEL);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new GridLayout(0, 1, 0, 8));
        actions.add(addFilesButton);
        actions.add(removeFilesButton);
        actions.add(clearQueueButton);

        content.add(actions, BorderLayout.NORTH);
        content.add(listScroll, BorderLayout.CENTER);

        JLabel dropHint = new JLabel("También puedes arrastrar video o audio directamente sobre la lista.");
        dropHint.setForeground(AppTheme.MUTED);
        dropHint.setFont(AppTheme.pickFont(13f, Font.PLAIN));
        content.add(dropHint, BorderLayout.SOUTH);

        return createCard(
            "Cola de trabajo",
            "Procesa una tanda completa y mantén el historial a la vista.",
            content
        );
    }

    private JComponent createOptionsCard() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 0);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;
        form.add(createFieldBlock("Carpeta de salida", outputDirectoryField, pickOutputButton, openOutputButton), gbc);
        gbc.gridy++;
        form.add(createLabeledCombo("Idioma", languageCombo), gbc);
        gbc.gridy++;
        form.add(createLabeledCombo("Modelo", modelCombo), gbc);
        gbc.gridy++;
        form.add(createLabeledCombo("Motor", deviceCombo), gbc);
        gbc.gridy++;
        form.add(createActionRow(), gbc);

        return createCard(
            "Configuración",
            "Pensada para inglés y español, con fallback automático a CPU.",
            form
        );
    }

    private JComponent createRightColumn() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, createTranscriptCard(), createLogCard());
        splitPane.setDividerLocation(470);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setOpaque(false);
        return splitPane;
    }

    private JComponent createTranscriptCard() {
        transcriptArea.setText("La transcripción en vivo aparecerá aquí cuando inicies la cola.");
        JScrollPane scrollPane = new JScrollPane(transcriptArea);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setBorder(BorderFactory.createLineBorder(AppTheme.BORDER, 1, true));
        return createCard(
            "Texto en vivo",
            "Útil para validar calidad antes de abrir los archivos finales TXT, SRT o JSON.",
            scrollPane
        );
    }

    private JComponent createLogCard() {
        logArea.setText("Actividad del backend y del orquestador.");
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setBorder(BorderFactory.createLineBorder(AppTheme.BORDER, 1, true));
        return createCard(
            "Actividad",
            "Mensajes del backend, errores y cambios de estado de la cola.",
            scrollPane
        );
    }

    private JComponent createFooterPanel() {
        JPanel footer = new JPanel(new BorderLayout(12, 12));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AppTheme.BORDER, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        JLabel primary = new JLabel("Salida: TXT, SRT y JSON en la misma carpeta.");
        primary.setFont(AppTheme.pickFont(13f, Font.BOLD));
        JLabel secondary = new JLabel("La GPU NVIDIA se usa si el backend detecta CUDA y el modo elegido lo permite.");
        secondary.setForeground(AppTheme.MUTED);
        secondary.setFont(AppTheme.pickFont(12f, Font.PLAIN));
        details.add(primary);
        details.add(Box.createVerticalStrut(2));
        details.add(secondary);

        footer.add(details, BorderLayout.WEST);
        footer.add(progressBar, BorderLayout.CENTER);
        return footer;
    }

    private JPanel createActionRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(startButton);
        row.add(Box.createHorizontalStrut(10));
        row.add(stopButton);
        return row;
    }

    private JPanel createFieldBlock(String labelText, JTextField field, JButton... buttons) {
        JPanel panel = new JPanel(new BorderLayout(10, 8));
        panel.setOpaque(false);
        JLabel label = new JLabel(labelText);
        label.setFont(AppTheme.pickFont(13f, Font.BOLD));
        panel.add(label, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);

        JPanel buttonRow = new JPanel();
        buttonRow.setOpaque(false);
        buttonRow.setLayout(new BoxLayout(buttonRow, BoxLayout.X_AXIS));
        for (int index = 0; index < buttons.length; index++) {
            if (index > 0) {
                buttonRow.add(Box.createHorizontalStrut(8));
            }
            buttonRow.add(buttons[index]);
        }
        panel.add(buttonRow, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createLabeledCombo(String labelText, JComboBox<String> comboBox) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        JLabel label = new JLabel(labelText);
        label.setFont(AppTheme.pickFont(13f, Font.BOLD));
        comboBox.setBackground(Color.WHITE);
        panel.add(label, BorderLayout.NORTH);
        panel.add(comboBox, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createCard(String title, String subtitle, JComponent content) {
        JPanel card = new JPanel(new BorderLayout(0, 14));
        card.setBackground(AppTheme.PANEL);
        card.setBorder(AppTheme.cardBorder());

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(AppTheme.pickFont(18f, Font.BOLD));
        JLabel subtitleLabel = new JLabel(subtitle);
        subtitleLabel.setForeground(AppTheme.MUTED);
        subtitleLabel.setFont(AppTheme.pickFont(13f, Font.PLAIN));
        header.add(titleLabel);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitleLabel);

        card.add(header, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JPanel metricCard(String title, JLabel valueLabel) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(255, 255, 255, 60), 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(new Color(226, 239, 235));
        titleLabel.setFont(AppTheme.pickFont(12f, Font.PLAIN));
        valueLabel.setForeground(Color.WHITE);
        valueLabel.setFont(AppTheme.pickFont(14f, Font.BOLD));

        panel.add(titleLabel, BorderLayout.NORTH);
        panel.add(valueLabel, BorderLayout.CENTER);
        return panel;
    }

    private JButton createButton(String text, boolean accent) {
        JButton button = new JButton(text);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(accent ? AppTheme.ACCENT_DARK : AppTheme.BORDER, 1, true),
            new EmptyBorder(10, 14, 10, 14)
        ));
        button.setBackground(accent ? AppTheme.ACCENT : Color.WHITE);
        button.setForeground(accent ? Color.WHITE : AppTheme.INK);
        return button;
    }

    private JTextArea createTextArea(boolean editable) {
        JTextArea area = new JTextArea();
        area.setEditable(editable);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(AppTheme.pickFont(14f, Font.PLAIN));
        area.setBackground(Color.WHITE);
        area.setForeground(AppTheme.INK);
        area.setBorder(new EmptyBorder(14, 14, 14, 14));
        return area;
    }

    private void configureActions() {
        addFilesButton.addActionListener(event -> openFileChooser());
        removeFilesButton.addActionListener(event -> removeSelectedJob());
        clearQueueButton.addActionListener(event -> clearQueue());
        pickOutputButton.addActionListener(event -> chooseOutputDirectory());
        openOutputButton.addActionListener(event -> openOutputDirectory());
        startButton.addActionListener(event -> startQueue());
        stopButton.addActionListener(event -> stopQueue());
    }

    private void openFileChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("Audio y video", MEDIA_EXTENSIONS));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            List<Path> paths = new ArrayList<>();
            for (File file : chooser.getSelectedFiles()) {
                paths.add(file.toPath());
            }
            addJobs(paths);
        }
    }

    private void chooseOutputDirectory() {
        JFileChooser chooser = new JFileChooser(outputDirectoryField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputDirectoryField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void openOutputDirectory() {
        try {
            Files.createDirectories(Path.of(outputDirectoryField.getText()));
            Desktop.getDesktop().open(Path.of(outputDirectoryField.getText()).toFile());
        } catch (Exception exception) {
            showError("No pude abrir la carpeta de salida.\n" + exception.getMessage());
        }
    }

    private void addJobs(List<Path> paths) {
        int added = 0;
        for (Path path : paths) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            if (containsPath(path)) {
                continue;
            }
            jobListModel.addElement(new TranscriptionJob(path.toAbsolutePath().normalize()));
            added++;
        }
        if (added > 0 && jobList.getSelectedIndex() < 0) {
            jobList.setSelectedIndex(0);
        }
        updateQueueBadge();
        appendLog("Se agregaron " + added + " archivo(s) a la cola.");
    }

    private boolean containsPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (int index = 0; index < jobListModel.size(); index++) {
            if (jobListModel.get(index).inputPath().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private void removeSelectedJob() {
        int index = jobList.getSelectedIndex();
        if (index >= 0 && !runningQueue) {
            jobListModel.remove(index);
            updateQueueBadge();
        }
    }

    private void clearQueue() {
        if (runningQueue) {
            showError("Detén la cola antes de vaciarla.");
            return;
        }
        jobListModel.clear();
        transcriptArea.setText("La transcripción en vivo aparecerá aquí cuando inicies la cola.");
        updateQueueBadge();
    }

    private void startQueue() {
        if (runningQueue) {
            return;
        }
        if (jobListModel.isEmpty()) {
            showError("Agrega al menos un archivo antes de iniciar.");
            return;
        }

        Path outputDirectory = Path.of(outputDirectoryField.getText()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(outputDirectory);
            Files.createDirectories(modelCacheDirectory);
        } catch (IOException exception) {
            showError("No pude preparar la carpeta de salida.\n" + exception.getMessage());
            return;
        }

        runningQueue = true;
        cancelRequested.set(false);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        setStatus("Cola en ejecución");
        appendLog("Inicio de cola con " + jobListModel.getSize() + " archivo(s).");

        List<TranscriptionJob> snapshot = new ArrayList<>();
        for (int index = 0; index < jobListModel.size(); index++) {
            snapshot.add(jobListModel.get(index));
        }

        Thread.ofVirtual().name("transcriptor-queue").start(() -> runQueue(snapshot, buildOptions(outputDirectory)));
    }

    private TranscriptionOptions buildOptions(Path outputDirectory) {
        return new TranscriptionOptions(
            outputDirectory,
            modelCacheDirectory,
            selectedLanguage(),
            String.valueOf(modelCombo.getSelectedItem()),
            selectedDevicePreference()
        );
    }

    private String selectedLanguage() {
        return switch (languageCombo.getSelectedIndex()) {
            case 1 -> "es";
            case 2 -> "en";
            default -> "auto";
        };
    }

    private String selectedDevicePreference() {
        return switch (deviceCombo.getSelectedIndex()) {
            case 1 -> "cuda";
            case 2 -> "cpu";
            default -> "auto";
        };
    }

    private void runQueue(List<TranscriptionJob> jobs, TranscriptionOptions options) {
        try {
            for (TranscriptionJob job : jobs) {
                if (cancelRequested.get()) {
                    markCanceled(job);
                    break;
                }
                processSingleJob(job, options);
            }
        } finally {
            SwingUtilities.invokeLater(() -> {
                runningQueue = false;
                activeJob = null;
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                progressBar.setIndeterminate(false);
                if (cancelRequested.get()) {
                    progressBar.setValue(0);
                    progressBar.setString("Cola detenida");
                    setStatus("Cola detenida");
                } else {
                    progressBar.setValue(100);
                    progressBar.setString("Cola terminada");
                    setStatus("Listo");
                }
            });
        }
    }

    private void processSingleJob(TranscriptionJob job, TranscriptionOptions options) {
        activeJob = job;
        SwingUtilities.invokeLater(() -> {
            job.clearTranscript();
            job.setErrorMessage("");
            job.setResult(null);
            job.setStatus(TranscriptionJob.Status.RUNNING);
            transcriptArea.setText("");
            jobList.repaint();
            progressBar.setIndeterminate(true);
            progressBar.setValue(0);
            progressBar.setString("Preparando " + job.inputPath().getFileName());
            setStatus("Procesando " + job.inputPath().getFileName());
        });

        try {
            TranscriptionResult result = engine.transcribe(job, options, cancelRequested, new UiListener(job));
            SwingUtilities.invokeLater(() -> {
                job.setResult(result);
                job.setStatus(TranscriptionJob.Status.DONE);
                progressBar.setIndeterminate(false);
                progressBar.setValue(100);
                progressBar.setString("Completado");
                setStatus("Listo: " + job.inputPath().getFileName());
                jobList.repaint();
                showSelectedJobTranscript();
            });
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            markCanceled(job);
        } catch (Exception exception) {
            SwingUtilities.invokeLater(() -> {
                job.setStatus(TranscriptionJob.Status.FAILED);
                job.setErrorMessage(exception.getMessage());
                progressBar.setIndeterminate(false);
                progressBar.setValue(0);
                progressBar.setString("Error");
                appendLog("Error en " + job.inputPath().getFileName() + ": " + exception.getMessage());
                setStatus("Error en la cola");
                jobList.repaint();
                if (job == activeJob) {
                    transcriptArea.setText("No se pudo generar la transcripción.\n\n" + exception.getMessage());
                }
            });
        }
    }

    private void stopQueue() {
        cancelRequested.set(true);
        engine.cancelActiveProcess();
        appendLog("Se solicitó detener la cola.");
    }

    private void markCanceled(TranscriptionJob job) {
        SwingUtilities.invokeLater(() -> {
            if (job.status() == TranscriptionJob.Status.QUEUED || job.status() == TranscriptionJob.Status.RUNNING) {
                job.setStatus(TranscriptionJob.Status.CANCELED);
            }
            progressBar.setIndeterminate(false);
            progressBar.setValue(0);
            progressBar.setString("Cancelado");
            jobList.repaint();
        });
    }

    private void showSelectedJobTranscript() {
        TranscriptionJob selected = jobList.getSelectedValue();
        if (selected == null) {
            return;
        }
        if (!selected.transcriptText().isBlank()) {
            transcriptArea.setText(selected.transcriptText());
            transcriptArea.setCaretPosition(0);
            return;
        }
        if (selected.result() != null && Files.exists(selected.result().txtPath())) {
            try {
                transcriptArea.setText(Files.readString(selected.result().txtPath()));
                transcriptArea.setCaretPosition(0);
                return;
            } catch (IOException ignored) {
            }
        }
        if (!selected.errorMessage().isBlank()) {
            transcriptArea.setText("No se pudo completar este trabajo.\n\n" + selected.errorMessage());
        }
    }

    private void setStatus(String text) {
        statusBadge.setText(text);
    }

    private void setEngineLabel(String text) {
        engineBadge.setText(text);
    }

    private void updateQueueBadge() {
        queueBadge.setText(jobListModel.getSize() + " archivos");
    }

    private void appendLog(String line) {
        SwingUtilities.invokeLater(() -> {
            if (!logArea.getText().isBlank()) {
                logArea.append(System.lineSeparator());
            }
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Transcriptor Local", JOptionPane.ERROR_MESSAGE);
    }

    private final class UiListener implements TranscriptionEngine.TranscriptionListener {
        private final TranscriptionJob job;

        private UiListener(TranscriptionJob job) {
            this.job = job;
        }

        @Override
        public void onStatus(String message) {
            SwingUtilities.invokeLater(() -> {
                progressBar.setString(message);
                if (job == activeJob) {
                    setStatus(message);
                }
            });
            appendLog(message);
        }

        @Override
        public void onEngineInfo(String device, String computeType, String model) {
            SwingUtilities.invokeLater(() -> {
                String engineText = (device + " · " + computeType + " · " + model).toUpperCase(Locale.ROOT);
                setEngineLabel(engineText);
                summaryLabel.setText("Backend activo: " + device + " con " + computeType + " y modelo " + model + ".");
            });
        }

        @Override
        public void onProgress(double progress, double processedSeconds, double totalSeconds) {
            SwingUtilities.invokeLater(() -> {
                progressBar.setIndeterminate(false);
                progressBar.setValue((int) Math.max(0, Math.min(100, Math.round(progress * 100.0))));
                if (totalSeconds > 0) {
                    progressBar.setString(
                        "Progreso "
                            + Math.round(progress * 100.0)
                            + "% · "
                            + SECONDS_FORMAT.format(processedSeconds)
                            + " / "
                            + SECONDS_FORMAT.format(totalSeconds)
                            + " s"
                    );
                }
            });
        }

        @Override
        public void onSegment(double startSeconds, double endSeconds, String text) {
            job.appendTranscript(text);
            SwingUtilities.invokeLater(() -> {
                if (job == activeJob || job.equals(jobList.getSelectedValue())) {
                    transcriptArea.setText(job.transcriptText());
                    transcriptArea.setCaretPosition(transcriptArea.getDocument().getLength());
                }
            });
        }

        @Override
        public void onResult(TranscriptionResult result) {
            appendLog(
                "Archivos listos: "
                    + result.txtPath().getFileName()
                    + ", "
                    + result.srtPath().getFileName()
                    + ", "
                    + result.jsonPath().getFileName()
            );
        }

        @Override
        public void onError(String message) {
            appendLog("Backend reportó error: " + message);
        }

        @Override
        public void onLog(String line) {
            appendLog(line);
        }
    }

    private final class JobRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
        ) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            TranscriptionJob job = (TranscriptionJob) value;
            String extra = switch (job.status()) {
                case DONE -> job.result() == null
                    ? "Salida generada"
                    : "TXT · SRT · JSON · " + job.result().language();
                case FAILED -> job.errorMessage().isBlank() ? "Revisa la consola del backend" : job.errorMessage();
                case RUNNING -> "Transcripción en vivo";
                case CANCELED -> "Trabajo detenido";
                case QUEUED -> "Esperando turno";
            };

            label.setText(
                "<html><div style='font-weight:700;'>"
                    + job.inputPath().getFileName()
                    + "</div><div style='margin-top:3px;color:#5d6674;'>"
                    + job.status().label()
                    + " · "
                    + extra
                    + "</div></html>"
            );
            label.setBorder(new EmptyBorder(10, 12, 10, 12));
            label.setBackground(isSelected ? new Color(222, 239, 233) : Color.WHITE);
            label.setForeground(AppTheme.INK);
            return label;
        }
    }

    private final class FileDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                && (support.getSourceDropActions() & DnDConstants.ACTION_COPY) != 0;
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                List<Path> paths = new ArrayList<>();
                for (File file : files) {
                    paths.add(file.toPath());
                }
                addJobs(paths);
                return true;
            } catch (Exception exception) {
                appendLog("No se pudieron importar los archivos arrastrados: " + exception.getMessage());
                return false;
            }
        }
    }

    private static final class GradientPanel extends JPanel {
        private GradientPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setPaint(new GradientPaint(0, 0, new Color(10, 72, 69), getWidth(), getHeight(), new Color(37, 110, 89)));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 28, 28);

            g2.setColor(new Color(255, 255, 255, 36));
            g2.fillOval(getWidth() - 210, -30, 260, 260);
            g2.setColor(new Color(245, 174, 83, 92));
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval(getWidth() - 170, 22, 128, 128);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }
}
