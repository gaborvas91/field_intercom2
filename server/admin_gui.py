"""
Admin dashboard for the intercom server.

Run this INSTEAD of server.py when you want the GUI:

    python admin_gui.py

It runs the exact same server logic (server.py) on a background thread, and
shows a Tkinter window on the main thread for monitoring and control:

  - Who's connected, who's currently talking, who's muted
  - Force-mute / unmute any phone
  - Send a text message to one phone or broadcast to everyone (phones show
    it on screen, vibrate, and play a distinct "message" tone)
  - A live log panel

No extra dependencies -- Tkinter ships with standard Python.
"""

import tkinter as tk
from tkinter import ttk, simpledialog, messagebox

import server as server_module


class AdminGUI:
    REFRESH_MS = 500

    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("Field Intercom -- Admin Dashboard")
        self.root.geometry("820x520")
        self.root.configure(bg="#121212")

        self.server = server_module.run_server_in_background_thread()

        self._build_widgets()
        self._refresh()

    def _build_widgets(self):
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("Treeview", background="#1e1e1e", fieldbackground="#1e1e1e",
                         foreground="#ffffff", rowheight=26)
        style.configure("Treeview.Heading", background="#2c3e50", foreground="#ffffff")
        style.map("Treeview", background=[("selected", "#d35400")])

        top = tk.Frame(self.root, bg="#121212")
        top.pack(fill="both", expand=True, padx=12, pady=12)

        # --- Client list ---
        columns = ("name", "status", "talking", "muted")
        self.tree = ttk.Treeview(top, columns=columns, show="headings", height=10)
        for col, label, width in [
            ("name", "Device", 220), ("status", "Status", 140),
            ("talking", "Talking", 100), ("muted", "Muted", 100),
        ]:
            self.tree.heading(col, text=label)
            self.tree.column(col, width=width)
        self.tree.pack(fill="both", expand=True, side="top")

        # --- Action buttons ---
        button_row = tk.Frame(self.root, bg="#121212")
        button_row.pack(fill="x", padx=12, pady=(0, 8))

        tk.Button(button_row, text="Mute selected", command=self.mute_selected,
                  bg="#c62828", fg="white").pack(side="left", padx=4)
        tk.Button(button_row, text="Unmute selected", command=self.unmute_selected,
                  bg="#1b7a3e", fg="white").pack(side="left", padx=4)
        tk.Button(button_row, text="Message selected...", command=self.message_selected,
                  bg="#2c3e50", fg="white").pack(side="left", padx=4)
        tk.Button(button_row, text="Broadcast to all...", command=self.broadcast_message,
                  bg="#8e44ad", fg="white").pack(side="left", padx=4)

        # --- Log panel ---
        log_label = tk.Label(self.root, text="Log", bg="#121212", fg="#aaaaaa", anchor="w")
        log_label.pack(fill="x", padx=12)
        self.log_text = tk.Text(self.root, height=10, bg="#1e1e1e", fg="#dddddd",
                                 insertbackground="#dddddd", state="disabled")
        self.log_text.pack(fill="both", expand=True, padx=12, pady=(0, 12))
        self._last_log_len = 0

    # ---------------- Helpers to talk to the network thread safely ----------------

    def _run_on_server_thread(self, fn, *args):
        loop = self.server.event_loop
        if loop is None:
            messagebox.showinfo("Not ready", "Server is still starting up -- try again in a second.")
            return
        loop.call_soon_threadsafe(fn, *args)

    def _selected_token(self):
        selection = self.tree.selection()
        if not selection:
            messagebox.showinfo("No selection", "Select a phone in the list first.")
            return None
        return int(selection[0])

    def mute_selected(self):
        token = self._selected_token()
        if token is not None:
            self._run_on_server_thread(self.server.mute_client, token)

    def unmute_selected(self):
        token = self._selected_token()
        if token is not None:
            self._run_on_server_thread(self.server.unmute_client, token)

    def message_selected(self):
        token = self._selected_token()
        if token is None:
            return
        text = simpledialog.askstring("Send message", "Message to send to this phone:")
        if text:
            self._run_on_server_thread(self.server.send_text_message, token, text)

    def broadcast_message(self):
        text = simpledialog.askstring("Broadcast message", "Message to send to every connected phone:")
        if text:
            self._run_on_server_thread(self.server.send_text_message, None, text)

    # ---------------- Periodic refresh ----------------

    def _refresh(self):
        selected = self.tree.selection()
        self.tree.delete(*self.tree.get_children())
        for token, client in self.server.clients.items():
            status = "Connected" if client.is_connected() else "Out of range"
            talking = "Talking" if client.is_talking else ""
            muted = "MUTED" if client.muted else ""
            self.tree.insert("", "end", iid=str(token),
                              values=(client.device_name, status, talking, muted))
        if selected:
            try:
                self.tree.selection_set(selected)
            except tk.TclError:
                pass  # that client disconnected since the last refresh

        new_lines = list(server_module.log_buffer)[self._last_log_len:]
        if new_lines:
            self.log_text.configure(state="normal")
            for line in new_lines:
                self.log_text.insert("end", line + "\n")
            self.log_text.see("end")
            self.log_text.configure(state="disabled")
            self._last_log_len = len(server_module.log_buffer)

        self.root.after(self.REFRESH_MS, self._refresh)


if __name__ == "__main__":
    root = tk.Tk()
    app = AdminGUI(root)
    root.mainloop()
