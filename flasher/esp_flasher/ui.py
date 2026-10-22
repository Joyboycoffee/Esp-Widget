"""Tkinter desktop UI for the ESP Widget USB flasher."""

from __future__ import annotations

import queue
import time
import tkinter as tk
import tkinter.font as tkfont
from datetime import datetime, timezone
from tkinter import ttk
from typing import Optional

from .core import (
    APP_NAME,
    APP_VERSION,
    FirmwareInfo,
    PortInfo,
    is_auto_candidate,
    list_serial_ports,
    load_firmware,
    open_in_file_manager,
    pick_auto_port,
)
from .flash import FlashWorker

# Obsidian + tactical amber, matching the widget's own theme.
BG = "#0A0E14"
CARD = "#121A24"
CARD_ALT = "#172231"
BORDER = "#223040"
TEXT = "#E9EFF7"
MUTED = "#8395A9"
DIM = "#5C6E82"
ACCENT = "#F3B44A"
ACCENT_HOVER = "#FFD17C"
GREEN = "#4ADE9A"
RED = "#FF6B7A"
BLUE = "#74C7FF"
TRACK = "#1C2836"

FONT = "Segoe UI"
MONO = "Consolas"


def _font(size: int, weight: str = "normal", family: str = FONT):
    return (family, size, weight)


def _tint(color: str, amount: float = 0.16) -> str:
    """Mix a colour with the card background for subtle badge fills."""

    base = CARD
    try:
        br, bg, bb = int(base[1:3], 16), int(base[3:5], 16), int(base[5:7], 16)
        cr, cg, cb = int(color[1:3], 16), int(color[3:5], 16), int(color[5:7], 16)
    except (ValueError, IndexError):
        return CARD_ALT
    r = int(br + (cr - br) * amount)
    g = int(bg + (cg - bg) * amount)
    b = int(bb + (cb - bb) * amount)
    return f"#{r:02X}{g:02X}{b:02X}"


class Pill(tk.Canvas):
    def __init__(self, master, height: int = 24, **kwargs):
        super().__init__(
            master,
            height=height,
            width=96,
            bg=master.cget("bg"),
            highlightthickness=0,
            bd=0,
            **kwargs,
        )
        self._height = height
        self._font = tkfont.Font(family=FONT, size=8, weight="bold")
        self._text = "WAITING"
        self._fg = MUTED
        self._fill = CARD_ALT
        self.set("WAITING", MUTED, CARD_ALT)

    def set(self, text: str, fg: str, fill: str) -> None:
        self._text = text
        width = max(78, self._font.measure(text) + 26)
        self.configure(width=width)
        self.delete("all")
        h = self._height
        w = width
        radius = h / 2
        self.create_oval(0, 0, h, h, fill=fill, outline="")
        self.create_oval(w - h, 0, w, h, fill=fill, outline="")
        self.create_rectangle(radius, 0, w - radius, h, fill=fill, outline="")
        self.create_text(
            w / 2,
            h / 2 + 1,
            text=text,
            fill=fg,
            font=self._font,
        )


class Meter(tk.Canvas):
    def __init__(self, master, height: int = 10, **kwargs):
        super().__init__(
            master,
            height=height,
            bg=master.cget("bg"),
            highlightthickness=0,
            bd=0,
            **kwargs,
        )
        self._value = 0.0
        self._color = ACCENT
        self._height = height
        self.bind("<Configure>", lambda _event: self._draw())

    def set(self, value: float, color: Optional[str] = None) -> None:
        self._value = max(0.0, min(100.0, float(value)))
        if color:
            self._color = color
        self._draw()

    def _draw(self) -> None:
        self.delete("all")
        width = self.winfo_width()
        if width <= 2:
            return
        pad = self._height / 2
        y = self._height / 2
        self.create_line(
            pad,
            y,
            width - pad,
            y,
            width=self._height,
            capstyle=tk.ROUND,
            fill=TRACK,
        )
        if self._value > 0:
            end = pad + (width - 2 * pad) * (self._value / 100.0)
            self.create_line(
                pad,
                y,
                max(pad + 1, end),
                y,
                width=self._height,
                capstyle=tk.ROUND,
                fill=self._color,
            )


class Switch(tk.Canvas):
    def __init__(self, master, variable: tk.BooleanVar, command=None, **kwargs):
        super().__init__(
            master,
            width=46,
            height=26,
            bg=master.cget("bg"),
            highlightthickness=0,
            bd=0,
            takefocus=True,
            cursor="hand2",
            **kwargs,
        )
        self._var = variable
        self._command = command
        self._enabled = True
        self.bind("<Button-1>", self._toggle)
        self.bind("<space>", self._toggle)
        self._trace = variable.trace_add("write", lambda *_: self._draw())
        self._draw()

    def set_enabled(self, enabled: bool) -> None:
        self._enabled = enabled
        self.configure(cursor="hand2" if enabled else "arrow")
        self._draw()

    def _toggle(self, _event=None):
        if not self._enabled:
            return "break"
        self._var.set(not self._var.get())
        if self._command:
            self._command()
        return "break"

    def _draw(self) -> None:
        self.delete("all")
        on = bool(self._var.get())
        if on and self._enabled:
            track = ACCENT
            knob = "#15100A"
        elif on:
            track = "#6B5735"
            knob = "#2A2418"
        elif self._enabled:
            track = "#2A3747"
            knob = "#C5D2E1"
        else:
            track = "#202A36"
            knob = "#566579"

        self.create_line(13, 13, 33, 13, width=20, capstyle=tk.ROUND, fill=track)
        x = 32 if on else 14
        self.create_oval(x - 8, 5, x + 8, 21, fill=knob, outline="")


class FlasherApp(tk.Tk):
    EMPTY_PORT = "No serial ports found"

    def __init__(self, demo: bool = False, auto_close_ms: int = 0):
        super().__init__()
        self.demo = demo
        self._closing = False
        self._busy = False
        self._ports: list[PortInfo] = []
        self._ports_by_label: dict[str, PortInfo] = {}
        self._combo_labels: list[str] = []
        self._present: dict[str, PortInfo] = {}
        self._auto_done_at: dict[str, float] = {}
        self._pending_auto = None
        self._active_port: Optional[str] = None
        self._last_success = False
        self._worker: Optional[FlashWorker] = None
        self._events: queue.Queue[tuple[str, object]] = queue.Queue()

        self._fw: Optional[FirmwareInfo] = None
        self._fw_error: Optional[str] = None
        try:
            self._fw = load_firmware()
        except Exception as exc:  # surfaced in the UI instead of crashing
            self._fw_error = str(exc)

        self._build_window()
        self._build_ui()
        self._render_firmware()
        self._set_board(None)
        self._set_status(
            "Waiting for your widget",
            "Plug the USB-C cable into the board and this computer.",
            "idle",
            "WAITING",
        )

        if self._fw_error:
            self._append_log(self._fw_error, "err")
            self._set_status(
                "Firmware bundle missing",
                self._fw_error,
                "error",
                "ERROR",
            )
            self.flash_btn.configure(state="disabled")

        self.protocol("WM_DELETE_WINDOW", self._on_close)
        self.after(100, self._drain_events)
        if demo:
            self.after(250, self._enter_demo)
        else:
            self.after(250, self._tick)
        if auto_close_ms:
            self.after(auto_close_ms, self.destroy)

    # ------------------------------------------------------------------ chrome
    def _build_window(self) -> None:
        self.title(f"{APP_NAME}  ·  v{APP_VERSION}")
        self.configure(bg=BG)
        self.geometry("940x760")
        self.minsize(840, 690)
        self.option_add("*TCombobox*Listbox.background", CARD_ALT)
        self.option_add("*TCombobox*Listbox.foreground", TEXT)
        self.option_add("*TCombobox*Listbox.selectBackground", ACCENT)
        self.option_add("*TCombobox*Listbox.selectForeground", "#15100A")
        self.option_add("*TCombobox*Listbox.font", _font(9))
        self.option_add("*TCombobox*Listbox.borderWidth", 0)

        try:
            self.iconbitmap(default=str(self._resource("app.ico")))
        except Exception:
            pass
        try:
            self._logo = tk.PhotoImage(file=str(self._resource("app.png")))
            self.iconphoto(True, self._logo)
        except Exception:
            self._logo = None
        try:
            self._logo_small = tk.PhotoImage(file=str(self._resource("app_48.png")))
        except Exception:
            self._logo_small = None

    def _resource(self, name: str):
        from .core import resource_path

        return resource_path(name)

    def _build_ui(self) -> None:
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure(
            "Dark.TCombobox",
            fieldbackground=CARD_ALT,
            background=CARD_ALT,
            foreground=TEXT,
            arrowcolor=ACCENT,
            bordercolor=BORDER,
            lightcolor=CARD_ALT,
            darkcolor=CARD_ALT,
            padding=7,
        )
        style.map(
            "Dark.TCombobox",
            fieldbackground=[("readonly", CARD_ALT), ("disabled", CARD)],
            foreground=[("readonly", TEXT), ("disabled", DIM)],
            selectbackground=[("readonly", CARD_ALT)],
            selectforeground=[("readonly", TEXT)],
            bordercolor=[("focus", ACCENT)],
        )
        style.configure(
            "Dark.Vertical.TScrollbar",
            background=CARD_ALT,
            troughcolor=BG,
            bordercolor=BG,
            arrowcolor=MUTED,
            darkcolor=CARD_ALT,
            lightcolor=CARD_ALT,
        )

        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(5, weight=1)

        self._build_header()
        self._build_status()
        self._build_info_cards()
        self._build_controls()
        self._build_activity_header()
        self._build_log()
        self._build_footer()

    def _build_header(self) -> None:
        header = tk.Frame(self, bg=BG)
        header.grid(row=0, column=0, sticky="ew", padx=26, pady=(20, 14))
        header.grid_columnconfigure(1, weight=1)

        if self._logo_small is not None:
            logo = tk.Label(header, image=self._logo_small, bg=BG, bd=0)
            logo.grid(row=0, column=0, rowspan=2, sticky="w", padx=(0, 14))
        else:
            mark = tk.Label(
                header,
                text="⚡",
                bg=BG,
                fg=ACCENT,
                font=_font(22, "bold"),
            )
            mark.grid(row=0, column=0, rowspan=2, sticky="w", padx=(0, 14))

        tk.Label(
            header,
            text="ESP WIDGET",
            bg=BG,
            fg=ACCENT,
            font=_font(8, "bold"),
        ).grid(row=0, column=1, sticky="sw")
        tk.Label(
            header,
            text="USB Flasher",
            bg=BG,
            fg=TEXT,
            font=_font(18, "bold"),
        ).grid(row=1, column=1, sticky="nw")

        right = tk.Frame(header, bg=BG)
        right.grid(row=0, column=2, rowspan=2, sticky="e")
        tk.Label(
            right,
            text=f"v{APP_VERSION}",
            bg=BG,
            fg=MUTED,
            font=_font(9),
        ).pack(anchor="e")
        tk.Label(
            right,
            text="ESP32-C3  ·  merged factory image",
            bg=BG,
            fg=DIM,
            font=_font(8),
        ).pack(anchor="e", pady=(3, 0))

    def _card(self, parent) -> tk.Frame:
        return tk.Frame(
            parent,
            bg=CARD,
            highlightbackground=BORDER,
            highlightthickness=1,
            bd=0,
        )

    def _build_status(self) -> None:
        card = self._card(self)
        card.grid(row=1, column=0, sticky="ew", padx=26, pady=(0, 12))
        inner = tk.Frame(card, bg=CARD)
        inner.pack(fill="x", padx=18, pady=16)

        top = tk.Frame(inner, bg=CARD)
        top.pack(fill="x")
        self._dot = tk.Canvas(
            top,
            width=18,
            height=18,
            bg=CARD,
            highlightthickness=0,
            bd=0,
        )
        self._dot.pack(side="left", padx=(0, 10))
        self._dot_id = self._dot.create_oval(3, 3, 15, 15, fill=MUTED, outline="")

        self.status_label = tk.Label(
            top,
            text="Waiting",
            bg=CARD,
            fg=TEXT,
            font=_font(14, "bold"),
            anchor="w",
        )
        self.status_label.pack(side="left")

        self.badge = Pill(top)
        self.badge.pack(side="right")

        self.detail_label = tk.Label(
            inner,
            text="",
            bg=CARD,
            fg=MUTED,
            font=_font(9),
            anchor="w",
            justify="left",
            wraplength=830,
        )
        self.detail_label.pack(fill="x", pady=(7, 13))

        self.meter = Meter(inner, height=10)
        self.meter.pack(fill="x")

        bottom = tk.Frame(inner, bg=CARD)
        bottom.pack(fill="x", pady=(9, 0))
        self.stage_var = tk.StringVar(value="Ready when you are")
        tk.Label(
            bottom,
            textvariable=self.stage_var,
            bg=CARD,
            fg=MUTED,
            font=_font(9),
            anchor="w",
        ).pack(side="left")
        self.percent_var = tk.StringVar(value="0%")
        tk.Label(
            bottom,
            textvariable=self.percent_var,
            bg=CARD,
            fg=TEXT,
            font=_font(9, "bold", MONO),
        ).pack(side="right")

    def _build_info_cards(self) -> None:
        row = tk.Frame(self, bg=BG)
        row.grid(row=2, column=0, sticky="ew", padx=26, pady=(0, 12))
        row.grid_columnconfigure(0, weight=1)
        row.grid_columnconfigure(1, weight=1)

        board = self._card(row)
        board.grid(row=0, column=0, sticky="nsew", padx=(0, 6))
        self._build_board_card(board)

        firmware = self._card(row)
        firmware.grid(row=0, column=1, sticky="nsew", padx=(6, 0))
        self._build_firmware_card(firmware)

    def _build_board_card(self, card: tk.Frame) -> None:
        inner = tk.Frame(card, bg=CARD)
        inner.pack(fill="both", expand=True, padx=16, pady=14)

        tk.Label(
            inner,
            text="CONNECTED BOARD",
            bg=CARD,
            fg=DIM,
            font=_font(8, "bold"),
            anchor="w",
        ).pack(fill="x")
        self.board_port = tk.Label(
            inner,
            text="Waiting for USB-C",
            bg=CARD,
            fg=TEXT,
            font=_font(13, "bold"),
            anchor="w",
        )
        self.board_port.pack(fill="x", pady=(5, 1))
        self.board_sub = tk.Label(
            inner,
            text="Plug the widget into this computer",
            bg=CARD,
            fg=MUTED,
            font=_font(8),
            anchor="w",
        )
        self.board_sub.pack(fill="x")

        self._divider(inner)
        fields = tk.Frame(inner, bg=CARD)
        fields.pack(fill="x")
        fields.grid_columnconfigure(0, weight=1)
        fields.grid_columnconfigure(1, weight=1)
        self.chip_value = self._field(fields, 0, "CHIP", "—")
        self.mac_value = self._field(fields, 1, "MAC ADDRESS", "—")

    def _build_firmware_card(self, card: tk.Frame) -> None:
        inner = tk.Frame(card, bg=CARD)
        inner.pack(fill="both", expand=True, padx=16, pady=14)

        tk.Label(
            inner,
            text="BUNDLED FIRMWARE",
            bg=CARD,
            fg=DIM,
            font=_font(8, "bold"),
            anchor="w",
        ).pack(fill="x")
        self.fw_version = tk.Label(
            inner,
            text="v—",
            bg=CARD,
            fg=TEXT,
            font=_font(13, "bold"),
            anchor="w",
        )
        self.fw_version.pack(fill="x", pady=(5, 1))
        self.fw_sub = tk.Label(
            inner,
            text="ESP32-C3 · merged image",
            bg=CARD,
            fg=MUTED,
            font=_font(8),
            anchor="w",
        )
        self.fw_sub.pack(fill="x")

        self._divider(inner)
        fields = tk.Frame(inner, bg=CARD)
        fields.pack(fill="x")
        fields.grid_columnconfigure(0, weight=1)
        fields.grid_columnconfigure(1, weight=1)
        fields.grid_columnconfigure(2, weight=1)
        self.fw_build = self._field(fields, 0, "BUILT", "—")
        self.fw_size = self._field(fields, 1, "IMAGE SIZE", "—")
        self.fw_sha = self._field(fields, 2, "SHA-256", "—")

    def _divider(self, parent) -> None:
        tk.Frame(parent, bg=BORDER, height=1).pack(fill="x", pady=12)

    def _field(self, parent, column: int, label: str, value: str) -> tk.Label:
        holder = tk.Frame(parent, bg=CARD)
        holder.grid(row=0, column=column, sticky="w", padx=(0, 12))
        tk.Label(
            holder,
            text=label,
            bg=CARD,
            fg=DIM,
            font=_font(7, "bold"),
            anchor="w",
        ).pack(fill="x")
        value_label = tk.Label(
            holder,
            text=value,
            bg=CARD,
            fg=TEXT,
            font=_font(9, "bold"),
            anchor="w",
        )
        value_label.pack(fill="x", pady=(3, 0))
        return value_label

    def _build_controls(self) -> None:
        card = self._card(self)
        card.grid(row=3, column=0, sticky="ew", padx=26, pady=(0, 12))
        inner = tk.Frame(card, bg=CARD)
        inner.pack(fill="x", padx=16, pady=14)
        inner.grid_columnconfigure(0, weight=3)
        inner.grid_columnconfigure(1, weight=2)

        options = tk.Frame(inner, bg=CARD)
        options.grid(row=0, column=0, sticky="nw", padx=(0, 18))

        self.auto_var = tk.BooleanVar(value=True)
        row = tk.Frame(options, bg=CARD)
        row.pack(fill="x")
        self.auto_switch = Switch(row, self.auto_var, command=self._on_auto_changed)
        self.auto_switch.pack(side="left", padx=(0, 10))
        labels = tk.Frame(row, bg=CARD)
        labels.pack(side="left", fill="x")
        tk.Label(
            labels,
            text="Auto-flash on connect",
            bg=CARD,
            fg=TEXT,
            font=_font(9, "bold"),
            anchor="w",
        ).pack(fill="x")
        tk.Label(
            labels,
            text="Detect the board and write the latest image immediately.",
            bg=CARD,
            fg=MUTED,
            font=_font(8),
            anchor="w",
        ).pack(fill="x", pady=(1, 0))

        self.bridges_var = tk.BooleanVar(value=False)
        row2 = tk.Frame(options, bg=CARD)
        row2.pack(fill="x", pady=(11, 0))
        self.bridge_switch = Switch(row2, self.bridges_var, command=self._on_bridge_changed)
        self.bridge_switch.pack(side="left", padx=(0, 10))
        labels2 = tk.Frame(row2, bg=CARD)
        labels2.pack(side="left", fill="x")
        tk.Label(
            labels2,
            text="Include USB-serial adapters",
            bg=CARD,
            fg=TEXT,
            font=_font(9, "bold"),
            anchor="w",
        ).pack(fill="x")
        tk.Label(
            labels2,
            text="Also auto-flash CH340, CP210x and FTDI boards.",
            bg=CARD,
            fg=MUTED,
            font=_font(8),
            anchor="w",
        ).pack(fill="x", pady=(1, 0))

        port_box = tk.Frame(inner, bg=CARD)
        port_box.grid(row=0, column=1, sticky="new")
        tk.Label(
            port_box,
            text="TARGET PORT",
            bg=CARD,
            fg=DIM,
            font=_font(8, "bold"),
            anchor="w",
        ).pack(fill="x")
        port_row = tk.Frame(port_box, bg=CARD)
        port_row.pack(fill="x", pady=(5, 0))
        self.port_var = tk.StringVar(value=self.EMPTY_PORT)
        self.port_combo = ttk.Combobox(
            port_row,
            textvariable=self.port_var,
            state="readonly",
            style="Dark.TCombobox",
            font=_font(9),
            values=[self.EMPTY_PORT],
            width=28,
        )
        self.port_combo.pack(side="left", fill="x", expand=True)
        self.port_combo.bind("<<ComboboxSelected>>", self._on_port_selected)
        self.rescan_btn = self._button(
            port_row, "Rescan", self._rescan, kind="secondary", padx=12, pady=7
        )
        self.rescan_btn.pack(side="left", padx=(6, 0))

        actions = tk.Frame(inner, bg=CARD)
        actions.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(14, 0))
        self.flash_btn = self._button(
            actions,
            "Flash now",
            self._flash_now,
            kind="primary",
            padx=22,
            pady=9,
        )
        self.flash_btn.pack(side="left")
        self.open_btn = self._button(
            actions,
            "Open release folder",
            self._open_release,
            kind="ghost",
            padx=12,
            pady=8,
        )
        self.open_btn.pack(side="left", padx=(10, 0))
        tk.Label(
            actions,
            text="One write covers bootloader, partitions and app.",
            bg=CARD,
            fg=DIM,
            font=_font(8),
            anchor="e",
        ).pack(side="right")

    def _build_activity_header(self) -> None:
        row = tk.Frame(self, bg=BG)
        row.grid(row=4, column=0, sticky="ew", padx=26, pady=(2, 7))
        tk.Label(
            row,
            text="ACTIVITY",
            bg=BG,
            fg=DIM,
            font=_font(8, "bold"),
        ).pack(side="left")
        self._ghost_button(row, "Copy log", self._copy_log).pack(side="right")
        self._ghost_button(row, "Clear", self._clear_log).pack(side="right", padx=(0, 6))

    def _build_log(self) -> None:
        card = self._card(self)
        card.grid(row=5, column=0, sticky="nsew", padx=26, pady=(0, 12))
        card.grid_rowconfigure(0, weight=1)
        card.grid_columnconfigure(0, weight=1)

        self.log = tk.Text(
            card,
            bg="#090E15",
            fg="#A9B8C9",
            insertbackground=ACCENT,
            selectbackground="#2A3A4C",
            selectforeground=TEXT,
            font=_font(9, family=MONO),
            wrap="word",
            relief="flat",
            bd=0,
            padx=14,
            pady=11,
            height=10,
            spacing1=1,
            spacing3=2,
            state="disabled",
        )
        self.log.grid(row=0, column=0, sticky="nsew")
        scroll = ttk.Scrollbar(
            card,
            orient="vertical",
            command=self.log.yview,
            style="Dark.Vertical.TScrollbar",
        )
        scroll.grid(row=0, column=1, sticky="ns")
        self.log.configure(yscrollcommand=scroll.set)

        self.log.tag_configure("info", foreground="#A9B8C9")
        self.log.tag_configure("muted", foreground="#62758A")
        self.log.tag_configure("ok", foreground=GREEN)
        self.log.tag_configure("warn", foreground=ACCENT)
        self.log.tag_configure("err", foreground=RED)
        self.log.tag_configure("bright", foreground=TEXT)

        self._append_log("Flasher ready. Waiting for an ESP32-C3 on USB-C.", "muted")

    def _build_footer(self) -> None:
        footer = tk.Frame(self, bg=BG)
        footer.grid(row=6, column=0, sticky="ew", padx=26, pady=(0, 18))
        footer.grid_columnconfigure(0, weight=1)
        tk.Label(
            footer,
            text=(
                "Won't connect? Hold BOOT, tap RESET, release BOOT, then retry. "
                "Close PlatformIO Monitor or any serial terminal first."
            ),
            bg=BG,
            fg=DIM,
            font=_font(8),
            anchor="w",
            justify="left",
            wraplength=700,
        ).grid(row=0, column=0, sticky="w")
        tk.Label(
            footer,
            text="No drivers or Python needed",
            bg=BG,
            fg=DIM,
            font=_font(8),
        ).grid(row=0, column=1, sticky="e")

    def _button(self, parent, text, command, kind="secondary", padx=16, pady=8):
        if kind == "primary":
            bg, fg = ACCENT, "#15100A"
            active_bg, active_fg = ACCENT_HOVER, "#15100A"
            font = _font(10, "bold")
        elif kind == "ghost":
            bg, fg = CARD, MUTED
            active_bg, active_fg = CARD_ALT, TEXT
            font = _font(8)
        else:
            bg, fg = CARD_ALT, TEXT
            active_bg, active_fg = "#213044", TEXT
            font = _font(8, "bold")
        return tk.Button(
            parent,
            text=text,
            command=command,
            bg=bg,
            fg=fg,
            activebackground=active_bg,
            activeforeground=active_fg,
            disabledforeground="#786A50" if kind == "primary" else DIM,
            font=font,
            bd=0,
            relief="flat",
            highlightthickness=0,
            padx=padx,
            pady=pady,
            cursor="hand2",
        )

    def _ghost_button(self, parent, text, command):
        return tk.Button(
            parent,
            text=text,
            command=command,
            bg=BG,
            fg=MUTED,
            activebackground=BG,
            activeforeground=TEXT,
            font=_font(8),
            bd=0,
            relief="flat",
            highlightthickness=0,
            cursor="hand2",
            padx=4,
            pady=0,
        )

    # --------------------------------------------------------------- rendering
    def _render_firmware(self) -> None:
        if self._fw is None:
            self.fw_version.configure(text="Not bundled")
            self.fw_sub.configure(text="Rebuild the flasher")
            return
        fw = self._fw
        self.fw_version.configure(text=f"v{fw.version}")
        self.fw_sub.configure(text=f"ESP32-C3  ·  merged factory image  ·  {fw.size_label}")
        self.fw_build.configure(text=_format_build(fw.built_utc))
        self.fw_size.configure(text=fw.size_label)
        self.fw_sha.configure(text=fw.short_hash)

    def _set_status(
        self,
        title: str,
        detail: str,
        state: str = "idle",
        badge: str = "WAITING",
    ) -> None:
        colors = {
            "idle": (MUTED, MUTED),
            "busy": (ACCENT, ACCENT),
            "ok": (GREEN, GREEN),
            "error": (RED, RED),
            "warn": (ACCENT, ACCENT),
        }
        dot_color, badge_color = colors.get(state, colors["idle"])
        self._dot.itemconfigure(self._dot_id, fill=dot_color)
        self.status_label.configure(text=title)
        self.detail_label.configure(text=detail)
        self.badge.set(badge, badge_color, _tint(badge_color, 0.16))

    def _set_board(self, port: Optional[PortInfo]) -> None:
        if port is None:
            self.board_port.configure(text="Waiting for USB-C", fg=TEXT)
            self.board_sub.configure(text="Plug the widget into this computer")
            self.chip_value.configure(text="—")
            self.mac_value.configure(text="—")
            return
        self.board_port.configure(text=port.device, fg=TEXT)
        self.board_sub.configure(text=f"{port.kind}  ·  {port.description or 'Serial port'}")
        self.chip_value.configure(text="ESP32-C3")
        self.mac_value.configure(text="—")

    def _set_busy(self, busy: bool) -> None:
        self._busy = busy
        self.auto_switch.set_enabled(not busy)
        self.bridge_switch.set_enabled(not busy)
        self.rescan_btn.configure(state="disabled" if busy else "normal")
        if busy:
            self.flash_btn.configure(state="disabled", text="Flashing…")
            self.port_combo.configure(state="disabled")
        else:
            self.flash_btn.configure(
                state="normal" if self._fw else "disabled",
                text="Flash again" if self._last_success else "Flash now",
            )
            self.port_combo.configure(
                state="readonly" if self._ports else "disabled"
            )

    def _append_log(self, text: str, level: str = "info") -> None:
        if not text:
            return
        timestamp = datetime.now().strftime("%H:%M:%S")
        self.log.configure(state="normal")
        self.log.insert("end", f"{timestamp}  ", "muted")
        self.log.insert("end", f"{text.rstrip()}\n", level)
        self.log.see("end")
        line_count = int(self.log.index("end-1c").split(".")[0])
        if line_count > 700:
            self.log.delete("1.0", "120.0")
        self.log.configure(state="disabled")

    # ------------------------------------------------------------------ events
    def _post(self, kind: str, payload: object) -> None:
        self._events.put((kind, payload))

    def _drain_events(self) -> None:
        if self._closing:
            return
        try:
            while True:
                kind, payload = self._events.get_nowait()
                if kind == "log":
                    text, level = payload  # type: ignore[misc]
                    self._append_log(str(text), str(level))
                elif kind == "stage":
                    self.stage_var.set(str(payload))
                elif kind == "progress":
                    value = int(payload)  # type: ignore[arg-type]
                    self.meter.set(value, ACCENT)
                    self.percent_var.set(f"{value}%")
                elif kind == "board":
                    self._on_board_event(payload)  # type: ignore[arg-type]
                elif kind == "done":
                    self._on_done_event(payload)  # type: ignore[arg-type]
                elif kind == "error":
                    self._on_error_event(str(payload))
        except queue.Empty:
            pass
        self.after(80, self._drain_events)

    def _on_board_event(self, payload: dict) -> None:
        port = str(payload.get("port") or "—")
        chip = str(payload.get("chip") or "ESP32-C3")
        mac = str(payload.get("mac") or "—")
        self.chip_value.configure(text=chip)
        self.mac_value.configure(text=mac)
        self.board_port.configure(text=port)
        self.board_sub.configure(text=f"{chip}  ·  connected")
        self.stage_var.set("Board ready")

    def _on_done_event(self, payload: dict) -> None:
        port = str(payload.get("port") or self._active_port or "—")
        chip = str(payload.get("chip") or "ESP32-C3")
        mac = str(payload.get("mac") or "—")
        version = str(payload.get("version") or APP_VERSION)
        elapsed = float(payload.get("elapsed") or 0.0)
        self._last_success = True
        self._auto_done_at[port] = time.monotonic()
        self._set_busy(False)
        self._set_status(
            "Firmware flashed",
            f"{chip} restarted on {port} with v{version} in {elapsed:.1f} s. "
            "The widget should be running now.",
            "ok",
            "DONE",
        )
        self.stage_var.set("Complete")
        self.percent_var.set("100%")
        self.meter.set(100, GREEN)
        self.chip_value.configure(text=chip)
        self.mac_value.configure(text=mac)
        self.board_port.configure(text=port)
        self.board_sub.configure(text=f"{chip}  ·  running v{version}")
        self.flash_btn.configure(text="Flash again")
        self._append_log(
            f"Done. {chip} on {port} restarted with v{version} in {elapsed:.1f} s.",
            "ok",
        )
        try:
            self.bell()
        except tk.TclError:
            pass

    def _on_error_event(self, message: str) -> None:
        self._set_busy(False)
        self._set_status("Flash failed", message, "error", "ERROR")
        self.stage_var.set("Stopped")
        self.meter.set(self.meter._value, RED)
        self.flash_btn.configure(text="Retry flash")
        if self._active_port:
            self._auto_done_at[self._active_port] = time.monotonic()
        self._append_log(message, "err")

    # ------------------------------------------------------------- port watcher
    def _tick(self) -> None:
        if self._closing:
            return
        ports = list_serial_ports()
        current = {port.device: port for port in ports}

        if not self._busy:
            for device, port in current.items():
                if (
                    device not in self._present
                    and is_auto_candidate(port, self.bridges_var.get())
                ):
                    self._on_insert(port)
            for device in list(self._present):
                if device not in current and is_auto_candidate(
                    self._present[device], self.bridges_var.get()
                ):
                    self._on_remove(device)

        self._present = current
        self._refresh_ports(ports)
        self.after(1000, self._tick)

    def _refresh_ports(self, ports: list[PortInfo]) -> None:
        self._ports = ports
        self._ports_by_label = {port.label: port for port in ports}
        labels = [port.label for port in ports]

        if labels != self._combo_labels:
            self._combo_labels = labels
            self.port_combo.configure(values=labels or [self.EMPTY_PORT])

        if not ports:
            if self.port_var.get() != self.EMPTY_PORT:
                self.port_var.set(self.EMPTY_PORT)
            if not self._busy:
                self.port_combo.configure(state="disabled")
            return

        if self.port_var.get() not in self._ports_by_label:
            preferred = pick_auto_port(ports, self.bridges_var.get()) or ports[0]
            self.port_var.set(preferred.label)

    def _on_insert(self, port: PortInfo) -> None:
        self._append_log(f"Detected {port.device} · {port.description or port.kind}", "info")
        self._set_board(port)
        candidate = is_auto_candidate(port, self.bridges_var.get())
        if candidate:
            self._set_status(
                "Board detected",
                f"{port.device} is connected." + (
                    " Starting the automatic flash…"
                    if self.auto_var.get()
                    else " Click Flash now to write the image."
                ),
                "ok",
                "READY",
            )
            if self.auto_var.get():
                self._schedule_auto(port)
        else:
            self._set_status(
                "Serial port detected",
                f"{port.device} is not recognized as an ESP32-C3. "
                "Select it above and click Flash now, or enable USB-serial adapters.",
                "warn",
                "SERIAL",
            )

    def _on_remove(self, device: str) -> None:
        if self._busy and device == self._active_port:
            self._append_log(
                f"{device} reset while flashing; waiting for it to come back…", "muted"
            )
            return
        self._append_log(f"{device} disconnected.", "muted")
        self._ports = [port for port in self._ports if port.device != device]
        if not self._busy:
            if not self._ports:
                self._set_board(None)
                self._set_status(
                    "Waiting for your widget",
                    "Plug the USB-C cable into the board and this computer.",
                    "idle",
                    "WAITING",
                )
                self.stage_var.set("Ready when you are")
            else:
                preferred = pick_auto_port(self._ports, self.bridges_var.get())
                self._set_board(preferred or self._ports[0])

    def _schedule_auto(self, port: PortInfo) -> None:
        self._cancel_pending_auto()
        self._pending_auto = self.after(
            1600, lambda: self._auto_flash_if_ready(port.device)
        )

    def _cancel_pending_auto(self) -> None:
        if self._pending_auto is not None:
            try:
                self.after_cancel(self._pending_auto)
            except tk.TclError:
                pass
            self._pending_auto = None

    def _auto_flash_if_ready(self, device: str) -> None:
        self._pending_auto = None
        if self._closing or self._busy or not self.auto_var.get():
            return
        ports = list_serial_ports()
        current = {port.device: port for port in ports}
        port = current.get(device)
        if port is None or not is_auto_candidate(port, self.bridges_var.get()):
            return
        last = self._auto_done_at.get(device, 0.0)
        if time.monotonic() - last < 25.0:
            return
        self._start_flash(port, auto=True)

    # ----------------------------------------------------------------- actions
    def _selected_port(self) -> Optional[PortInfo]:
        port = self._ports_by_label.get(self.port_var.get())
        if port is not None:
            return port
        return pick_auto_port(self._ports, self.bridges_var.get()) or (
            self._ports[0] if self._ports else None
        )

    def _flash_now(self) -> None:
        if self._busy:
            return
        port = self._selected_port()
        if port is None:
            self._set_status(
                "No board selected",
                "Plug in the USB-C cable and wait for the port to appear.",
                "warn",
                "NO BOARD",
            )
            self._append_log("No serial port selected.", "warn")
            try:
                self.bell()
            except tk.TclError:
                pass
            return
        if not is_auto_candidate(port, self.bridges_var.get()):
            self._append_log(
                f"Trying {port.device} even though it is not recognized as an ESP32.", "warn"
            )
        self._start_flash(port, auto=False)

    def _start_flash(self, port: PortInfo, auto: bool) -> None:
        if self._busy or self._fw is None:
            if self._fw is None:
                self._append_log("The bundled firmware is unavailable.", "err")
            return
        self._cancel_pending_auto()
        self._active_port = port.device
        self._set_busy(True)
        self.meter.set(0, ACCENT)
        self.percent_var.set("0%")
        self.stage_var.set("Connecting…")
        self._set_status(
            "Flashing firmware",
            f"Writing v{self._fw.version} to {port.device}. Keep the cable connected.",
            "busy",
            "FLASHING",
        )
        self._append_log("", "muted")
        self._append_log(
            f"Flash started on {port.device} · image v{self._fw.version} "
            f"({self._fw.size_label}, {self._fw.short_hash})",
            "bright",
        )
        self._worker = FlashWorker(port.device, self._fw, self._post, auto=auto)
        self._worker.start()

    def _on_auto_changed(self) -> None:
        if self.auto_var.get():
            self._append_log("Auto-flash enabled.", "info")
            if not self._busy:
                candidate = pick_auto_port(self._ports, self.bridges_var.get())
                if candidate is not None:
                    last = self._auto_done_at.get(candidate.device, 0.0)
                    if time.monotonic() - last > 25.0:
                        self._set_status(
                            "Board detected",
                            f"{candidate.device} is connected. Starting the automatic flash…",
                            "ok",
                            "READY",
                        )
                        self._schedule_auto(candidate)
        else:
            self._cancel_pending_auto()
            self._append_log("Auto-flash disabled.", "info")

    def _on_bridge_changed(self) -> None:
        enabled = self.bridges_var.get()
        self._append_log(
            "USB-serial adapters " + ("included." if enabled else "ignored."),
            "info",
        )
        if enabled and self.auto_var.get() and not self._busy:
            candidate = pick_auto_port(self._ports, include_bridges=True)
            if candidate is not None and not candidate.is_espressif:
                last = self._auto_done_at.get(candidate.device, 0.0)
                if time.monotonic() - last > 25.0:
                    self._set_status(
                        "Board detected",
                        f"{candidate.device} is connected through {candidate.bridge_name}. "
                        "Starting the automatic flash…",
                        "ok",
                        "READY",
                    )
                    self._schedule_auto(candidate)

    def _on_port_selected(self, _event=None) -> None:
        port = self._ports_by_label.get(self.port_var.get())
        if port is not None:
            self._set_board(port)
            self._append_log(f"Target port set to {port.device}.", "muted")

    def _rescan(self) -> None:
        ports = list_serial_ports()
        self._refresh_ports(ports)
        self._append_log(
            f"Rescanned USB ports: {len(ports)} serial device"
            + ("" if len(ports) == 1 else "s") + " found.",
            "muted",
        )
        if not ports:
            self._set_status(
                "No serial ports found",
                "Check the data cable and the USB-C connection. Some cables are power-only.",
                "warn",
                "NO BOARD",
            )

    def _copy_log(self) -> None:
        try:
            content = self.log.get("1.0", "end-1c")
            self.clipboard_clear()
            self.clipboard_append(content)
            self._append_log("Activity log copied to the clipboard.", "muted")
        except tk.TclError:
            pass

    def _clear_log(self) -> None:
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        self.log.configure(state="disabled")
        self._append_log("Log cleared.", "muted")

    def _open_release(self) -> None:
        from .core import app_directory

        open_in_file_manager(app_directory())

    def _enter_demo(self) -> None:
        port = PortInfo(
            device="COM7",
            description="USB JTAG/serial debug unit",
            vid=0x303A,
            pid=0x1001,
            hwid="USB VID_303A&PID_1001",
        )
        self._ports = [port]
        self._present = {port.device: port}
        self._ports_by_label = {port.label: port}
        self._combo_labels = [port.label]
        self.port_combo.configure(values=[port.label], state="readonly")
        self.port_var.set(port.label)
        self._set_board(port)
        self._set_status(
            "Board detected",
            f"{port.device} is connected. Auto-flash will start in a moment.",
            "ok",
            "READY",
        )
        self.stage_var.set("Demo mode — hardware is not touched")
        self._append_log("Demo mode: showing a simulated ESP32-C3 on COM7.", "warn")

    def _on_close(self) -> None:
        if self._busy:
            from tkinter import messagebox

            quit_anyway = messagebox.askyesno(
                "Flash in progress",
                "The board is still being flashed. Quitting now can leave it in "
                "download mode. Quit anyway?",
                parent=self,
            )
            if not quit_anyway:
                return
        self._closing = True
        self.destroy()


def _format_build(value: str) -> str:
    if not value:
        return "—"
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone().strftime("%d %b %Y · %H:%M")
    except ValueError:
        return value[:16]


def run_app(demo: bool = False, auto_close_ms: int = 0) -> int:
    app = FlasherApp(demo=demo, auto_close_ms=auto_close_ms)
    app.mainloop()
    return 0
