#!/usr/bin/env python3
"""Convierte vídeo MP4/MKV/MOV a MJPEG de Small-Home + índice de saltos.

Archivos de salida:
  movie.mjpeg  - fotogramas JPEG baseline concatenados
  movie.mji    - Small-Home Video Index v1 (tiempos + un offset por segundo)

El firmware del ESP32-C6 usa el .mji para la duración exacta, reanudar y saltar +/-10 s.

Uso:
  python video_to_mjpeg.py                 -> abre una ventana para elegir el vídeo
  python video_to_mjpeg.py pelicula.mkv    -> convierte ese archivo directamente
"""

from __future__ import annotations

import argparse
import math
import mmap
import os
import re
import shutil
import struct
import subprocess
import sys
import unicodedata
from pathlib import Path

MAGIC = b"SHVI"
VERSION = 1
INDEX_INTERVAL_MS = 1000

# Layout que espera el firmware (26 bytes, little-endian, sin relleno):
# magic, version, fps*100, frames, duracion_ms, ancho, alto, intervalo_ms, entradas
# NO lo cambies sin cambiar también el firmware.
HEADER = struct.Struct("<4sHHIIHHHI")
assert HEADER.size == 26

# Archivo más grande posible en FAT32 (= 0xFFFFFFFF, que también es el máximo
# de un offset de 32 bits).
FAT32_LIMIT = 4 * 1024**3 - 1

# SOI (FF D8) + inicio del siguiente marcador (FF). Dentro de los datos
# entropy-coded un FF siempre va seguido de 00 o RSTn, así que no hay falsos positivos.
JPEG_SOI = b"\xff\xd8\xff"

VIDEO_TYPES = [
    ("Vídeos", "*.mp4 *.mkv *.mov *.m4v *.avi *.webm *.ts"),
    ("Todos los archivos", "*.*"),
]


# --------------------------------------------------------------------------- #
# Utilidades
# --------------------------------------------------------------------------- #
def require_tool(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise SystemExit(
            f"No encuentro {name}. Instala FFmpeg y asegúrate de que {name} está en PATH."
        )
    return path


def human_size(n: int) -> str:
    value = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.1f} {unit}"
        value /= 1024
    return f"{value:.1f} GB"


def safe_name(name: str) -> str:
    """Nombre solo ASCII (letras, números, _ y -): seguro para FAT en la microSD."""
    name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode()
    name = re.sub(r"[^A-Za-z0-9_-]+", "_", name).strip("_")
    return name or "video"


# --------------------------------------------------------------------------- #
# Diálogos (tkinter)
# --------------------------------------------------------------------------- #
def pick_video_file() -> Path | None:
    """Abre el explorador de archivos. Devuelve None si se cancela.

    Lanza RuntimeError si no hay entorno gráfico / tkinter.
    """
    try:
        import tkinter as tk
        from tkinter import filedialog

        root = tk.Tk()
    except Exception as e:  # ImportError, TclError (sin pantalla), etc.
        raise RuntimeError("tkinter no disponible") from e

    try:
        root.withdraw()
        root.attributes("-topmost", True)
        root.update()
        chosen = filedialog.askopenfilename(
            parent=root,
            title="Elige el vídeo a convertir",
            filetypes=VIDEO_TYPES,
        )
    finally:
        root.destroy()
    return Path(chosen) if chosen else None


def ask_yes_no(question: str, use_dialog: bool) -> bool:
    if use_dialog:
        try:
            import tkinter as tk
            from tkinter import messagebox

            root = tk.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            try:
                return bool(messagebox.askyesno("Small-Home", question, parent=root))
            finally:
                root.destroy()
        except Exception:
            pass  # cae a la consola
    if sys.stdin and sys.stdin.isatty():
        answer = input(f"{question} [s/N]: ").strip().lower()
        return answer in {"s", "si", "sí", "y", "yes"}
    return False


def confirm_overwrite(paths: list[Path], assume_yes: bool, use_dialog: bool) -> None:
    existing = [p for p in paths if p.exists()]
    if not existing or assume_yes:
        return
    names = ", ".join(p.name for p in existing)
    if not ask_yes_no(f"Ya existe: {names}\n¿Sobrescribir?", use_dialog):
        raise SystemExit(
            "Cancelado: no se sobrescribió nada (usa --overwrite para forzarlo)."
        )


# --------------------------------------------------------------------------- #
# Conversión
# --------------------------------------------------------------------------- #
def run_ffmpeg(src: Path, dst: Path, fps: int, quality: int, width: int, height: int) -> None:
    ffmpeg = require_tool("ffmpeg")
    vf = (
        f"fps={fps},"
        f"scale={width}:{height}:force_original_aspect_ratio=decrease:force_divisible_by=2,"
        f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:black"
    )
    cmd = [
        ffmpeg,
        "-hide_banner",
        "-y",
        "-i", str(src),
        "-map", "0:v:0",          # primer stream de vídeo, sin adivinar
        "-an", "-sn", "-dn",      # sin audio, subtítulos ni datos
        "-vf", vf,
        "-c:v", "mjpeg",
        "-pix_fmt", "yuvj420p",
        "-q:v", str(quality),
        "-f", "mjpeg",
        str(dst),
    ]
    print("Convirtiendo a MJPEG...")
    subprocess.run(cmd, check=True)


def scan_jpeg_starts(path: Path) -> list[int]:
    """Posición de cada fotograma JPEG usando mmap (segundos, no minutos)."""
    starts: list[int] = []
    if path.stat().st_size == 0:
        return starts
    with path.open("rb") as f, mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as mm:
        pos = mm.find(JPEG_SOI)
        while pos != -1:
            starts.append(pos)
            pos = mm.find(JPEG_SOI, pos + len(JPEG_SOI))
    return starts


def write_index(mjpeg: Path, index: Path, fps: int, width: int, height: int) -> tuple[int, float]:
    print("Creando índice de salto/reanudación...")
    starts = scan_jpeg_starts(mjpeg)
    if not starts:
        raise RuntimeError("No se encontraron fotogramas JPEG en el MJPEG generado")

    frame_count = len(starts)
    duration_s = frame_count / float(fps)
    duration_ms = int(round(duration_s * 1000.0))
    entry_count = max(1, math.floor(duration_ms / INDEX_INTERVAL_MS) + 1)

    offsets: list[int] = []
    for entry in range(entry_count):
        sec = (entry * INDEX_INTERVAL_MS) / 1000.0
        frame = min(frame_count - 1, int(sec * fps))
        offsets.append(starts[frame])

    if offsets[-1] > 0xFFFFFFFF:
        raise RuntimeError("Un offset supera 32 bits; el formato v1 no puede indexarlo")

    with index.open("wb") as f:
        f.write(
            HEADER.pack(
                MAGIC,
                VERSION,
                fps * 100,
                frame_count,
                duration_ms,
                width,
                height,
                INDEX_INTERVAL_MS,
                len(offsets),
            )
        )
        for off in offsets:
            f.write(struct.pack("<I", off))

    return frame_count, duration_s


# --------------------------------------------------------------------------- #
# Programa principal
# --------------------------------------------------------------------------- #
def run(interactive: bool) -> None:
    parser = argparse.ArgumentParser(
        description="Convierte un vídeo a MJPEG optimizado para Small-Home / ESP32-C6."
    )
    parser.add_argument("input", type=Path, nargs="?", default=None,
                        help="MP4/MKV/MOV de entrada (si lo omites, se abre un selector de archivos)")
    parser.add_argument("-o", "--output-dir", type=Path, default=None,
                        help="Carpeta de salida (por defecto, junto al vídeo)")
    parser.add_argument("--fps", type=int, default=15, choices=range(10, 25), metavar="10-24",
                        help="FPS de salida. Recomendado: 15 (default)")
    parser.add_argument("--quality", type=int, default=12, choices=range(2, 32), metavar="2-31",
                        help="Calidad JPEG FFmpeg: menor = más calidad/peso. Default 12")
    parser.add_argument("--width", type=int, default=320)
    parser.add_argument("--height", type=int, default=172)
    parser.add_argument("--name", type=str, default=None,
                        help="Nombre base de salida, sin extensión")
    parser.add_argument("--overwrite", action="store_true",
                        help="Sobrescribir salidas existentes sin preguntar")
    args = parser.parse_args()

    for label, value in (("--width", args.width), ("--height", args.height)):
        if not 2 <= value <= 65535 or value % 2:
            parser.error(f"{label} debe ser un número par entre 2 y 65535 (yuv420p)")

    # --- elegir el vídeo ---------------------------------------------------- #
    used_dialog = args.input is None
    if used_dialog:
        try:
            src = pick_video_file()
        except RuntimeError:
            raw = input("Ruta del vídeo: ").strip().strip('"').strip("'")
            src = Path(raw) if raw else None
        if src is None:
            raise SystemExit("No se eligió ningún vídeo.")
    else:
        src = args.input

    src = src.expanduser().resolve()
    if not src.is_file():
        parser.error(f"No existe: {src}")
    print(f"Vídeo elegido: {src}")

    # --- rutas de salida ---------------------------------------------------- #
    out_dir = (args.output_dir or src.parent).expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    stem = safe_name(args.name or src.stem)
    if len(stem) > 8:
        print(f"Aviso: '{stem}' tiene más de 8 caracteres. Si tu firmware usa FATFS "
              "sin nombres largos (LFN), usa --name con un máximo de 8.")
    mjpeg = out_dir / f"{stem}.mjpeg"
    index = out_dir / f"{stem}.mji"
    if src in (mjpeg, index):
        raise SystemExit("El archivo de salida coincidiría con el de entrada; cambia --name.")

    confirm_overwrite([mjpeg, index], args.overwrite, used_dialog)

    def cleanup() -> None:
        for p in (mjpeg, index):
            p.unlink(missing_ok=True)

    # --- conversión --------------------------------------------------------- #
    try:
        run_ffmpeg(src, mjpeg, args.fps, args.quality, args.width, args.height)

        size = mjpeg.stat().st_size
        if size > FAT32_LIMIT:
            raise RuntimeError(
                f"El MJPEG ocupa {human_size(size)} y supera el límite de 4 GiB de FAT32 "
                "(y de los offsets de 32 bits). Baja --fps o SUBE el valor de --quality "
                "(más número = menos peso) y vuelve a intentarlo."
            )

        frames, duration = write_index(mjpeg, index, args.fps, args.width, args.height)
    except KeyboardInterrupt:
        cleanup()
        raise SystemExit("\nCancelado; he borrado los archivos incompletos.")
    except subprocess.CalledProcessError as e:
        cleanup()
        raise SystemExit(f"FFmpeg terminó con error ({e.returncode}).") from e
    except Exception as e:
        cleanup()
        raise SystemExit(f"Error: {e}") from e

    print("\nListo.")
    print(f"  Video : {mjpeg}")
    print(f"  Índice: {index}")
    print(f"  Frames: {frames}")
    print(f"  FPS   : {args.fps}")
    print(f"  Durac.: {duration / 60:.1f} min")
    print(f"  Tamaño: {human_size(size)}")
    print("\nCopia AMBOS archivos a /mjpeg en la microSD.")


def main() -> None:
    interactive = len(sys.argv) == 1
    try:
        run(interactive)
    finally:
        # En Windows, al hacer doble clic la consola se cierra y no verías el resultado.
        if interactive and os.name == "nt":
            try:
                input("\nPulsa Enter para cerrar...")
            except EOFError:
                pass


if __name__ == "__main__":
    main()
