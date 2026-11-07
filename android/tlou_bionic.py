#!/usr/bin/env python3
"""TLOU Remastered game-side preparation for an Android/Bionic ARM64 port.

This does not substitute the x86-64 Windows renderer/loader with the GOW3 renderer.
It creates the verified game image and version-gated patches used by a future
TLOU-specific FEX/ARM64 loader. No game content is shipped with the port.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
from pathlib import Path
import shutil
import struct
import subprocess
import sys

TITLE_ID = "CUSA00552"
# This exact base SELF is shared by the newly provided executable; accept
# alternative regional SFO identifiers only when the game executable matches
# byte-for-byte. The patch writer independently validates the same SHA.
VERIFIED_SELF_SHA256 = "e5f0aa322f60bd90637cc10d83de08c77885b1921084021c630e04ed3d6febf7"
VERIFIED_SELF_111 = {
    "3cfa475f88ca68485b6b9230e62b84e195cdbe19e6f7295c110b8dcf8f20884e",
    "31f3b0d120803878d10a7184d139c545de334394dd7dd6ca6058a7c995e484a7",
}
VERIFIED_TLOU_SELFS = frozenset({VERIFIED_SELF_SHA256} | VERIFIED_SELF_111)
REGIONAL_TITLES = {"CUSA00552", "CUSA00556", "CUSA00557", "CUSA00559"}
_BOOT_FILES = ("eboot.bin", "sce_sys/param.sfo", "sce_module/libc.prx")


class PortError(Exception):
    pass


def read_sfo(path: Path) -> dict[str, str]:
    """Read the bounded subset of PSF needed to reject mixed-title updates."""
    raw = path.read_bytes()
    if len(raw) < 20 or len(raw) > 1024 * 1024 or raw[:4] != b"\x00PSF":
        raise PortError(f"Invalid param.sfo: {path}")
    _, key_start, value_start, count = struct.unpack_from("<4I", raw, 4)
    if count > 4096 or 20 + count * 16 > len(raw):
        raise PortError(f"Invalid SFO index: {path}")
    result: dict[str, str] = {}
    for i in range(count):
        off = 20 + i * 16
        key_off, fmt, length, _, data_off = struct.unpack_from("<HHIII", raw, off)
        start = key_start + key_off
        end = value_start + data_off
        if not (0 <= start < len(raw)) or length > len(raw) or end > len(raw) - length:
            raise PortError(f"Invalid SFO entry: {path}")
        stop = raw.find(b"\x00", start)
        if stop == -1 or stop > value_start:
            raise PortError(f"Unterminated SFO key: {path}")
        key = raw[start:stop].decode("utf-8", errors="replace")
        if fmt == 0x0404 and length == 4:
            val = str(struct.unpack_from("<I", raw, end)[0])
        else:
            val = raw[end:end+length].split(b"\x00", 1)[0].decode("utf-8", errors="replace")
        result[key] = val
    return result


def require_game(folder: Path, *, is_update: bool = False) -> dict[str, str]:
    for relative in _BOOT_FILES:
        # A patch folder may omit the original libc; it inherits the base copy.
        if is_update and relative.startswith("sce_module/"):
            continue
        if not (folder / relative).is_file():
            raise PortError(f"Missing {relative}: {folder}")
    metadata = read_sfo(folder / "sce_sys/param.sfo")
    title = metadata.get("TITLE_ID")
    if title != TITLE_ID:
        # Regional TITLE_ID alone does not prove compatible execution.
        if (is_update or title not in REGIONAL_TITLES or
            hashlib.sha256((folder / "eboot.bin").read_bytes()).hexdigest() not in VERIFIED_TLOU_SELFS):
            raise PortError(f"Wrong PS4 title: {title!r}; expected verified TLOU region")
        metadata["PORT_PROFILE"] = "TLOU-VERIFIED-SELF-SHA256"
        print(f"Recognized TLOU regional SFO {title!r} via verified SELF.", flush=True)
    return metadata


def _case_path(base: Path, *parts: str) -> Path:
    """Resolve a conventional extracted-game path without depending on host case."""
    current = base
    for part in parts:
        direct = current / part
        if direct.exists():
            current = direct
            continue
        if not current.is_dir():
            return direct
        match = next((entry for entry in current.iterdir()
                      if entry.name.casefold() == part.casefold()), None)
        if match is None:
            return direct
        current = match
    return current


def find_asset_root(folder: Path) -> Path | None:
    """Find the directory that must be mounted as guest /app0."""
    candidates = [
        folder,
        _case_path(folder, "dvdroot_ps4"),
        _case_path(folder, "app0"),
        _case_path(folder, "PS4_GAME", "USRDIR", "dvdroot_ps4"),
        _case_path(folder, "PS4_GAME", "USRDIR"),
        _case_path(folder, "USRDIR", "dvdroot_ps4"),
        _case_path(folder, "USRDIR"),
    ]
    seen, valid = set(), []
    for order, root in enumerate(candidates):
        try:
            key = root.resolve()
        except OSError:
            key = root
        if key in seen:
            continue
        seen.add(key)
        main = root / "build/ps4/main"
        if not main.is_dir():
            continue
        score = sum((main / name).is_file() for name in
                    ("common-textures.psarc", "core-textures.psarc"))
        score += 2 if (main / "lut0/default-color-gray.dds").is_file() else 0
        score += 1 if (main / "boot1/coin-3d.dds").is_file() else 0
        valid.append((score, -order, root))
    return max(valid, default=(0, 0, None))[2]


def validate_game_assets(folder: Path) -> Path:
    """Locate the full game resource root and validate any PSARC archives."""
    root = find_asset_root(folder)
    if root is None:
        checked = ("build/ps4/main", "dvdroot_ps4/build/ps4/main", "app0/build/ps4/main",
                   "PS4_GAME/USRDIR/dvdroot_ps4/build/ps4/main", "USRDIR/dvdroot_ps4/build/ps4/main")
        raise PortError(
            "Executável TLOU encontrado, mas a raiz de recursos do jogo não foi localizada. "
            "O runtime precisa de build/ps4/main diretamente em /app0; verifique se o PKG "
            "foi extraído por completo. Caminhos verificados: " + ", ".join(checked))
    main = root / "build/ps4/main"
    archives = 0
    for archive in main.iterdir():
        if not archive.is_file() or not archive.name.casefold().endswith(".psarc"):
            continue
        with archive.open("rb") as data:
            header = data.read(32)
        size = archive.stat().st_size
        if len(header) != 32 or header[:4] != b"PSAR":
            raise PortError(f"Arquivo PSARC inválido ou truncado: {archive.name}. Reextraia o PKG.")
        toc_size, entry_size, file_count, block_size = struct.unpack_from(">4I", header, 12)
        if (not 30 <= entry_size <= 256 or file_count == 0 or file_count > 1_000_000 or
            block_size == 0 or block_size > 16 * 1024 * 1024 or
            toc_size < 32 + entry_size * file_count or toc_size > size):
            raise PortError(f"PSARC com índice incompleto ou inconsistente: {archive.name}. Reextraia o PKG.")
        archives += 1
    missing = [name for name in ("common-textures.psarc", "core-textures.psarc")
               if not (main / name).is_file()]
    if missing:
        loose_markers = int((main / "boot1/coin-3d.dds").is_file()) + int(
            (main / "lut0/default-color-gray.dds").is_file())
        if not archives and not loose_markers:
            raise PortError(
                f"A raiz /app0/build/ps4/main foi encontrada em {root}, mas os PSARC comuns "
                "e os recursos LUT/boot1 estão ausentes. A instalação parece conter apenas "
                "os arquivos de boot; o jogo não pode renderizar sem os recursos completos.")
        print("Aviso: PSARC(s) ausente(s): " + ", ".join(missing) +
              "; mantendo recursos soltos verificados.", flush=True)
    print(f"Runtime /app0 resource root: {root} ({archives} PSARC archive(s))", flush=True)
    return root


def _link_file(source: Path, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.is_symlink() or dest.is_file():
        dest.unlink()
    dest.symlink_to(source.resolve())


def _link_entry(source: Path, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.is_symlink() or dest.is_file():
        dest.unlink()
    elif dest.is_dir():
        shutil.rmtree(dest)
    dest.symlink_to(source.resolve(), target_is_directory=source.is_dir())


def build_view(game: Path, update: Path | None, out: Path) -> Path:
    """Build a read-only app0 view and flatten dvdroot/app0 resources to /app0."""
    asset_root = find_asset_root(game)
    if asset_root is None:
        raise PortError("Não é possível montar /app0: build/ps4/main não foi localizado.")
    view = out / "tlou-view"
    if view.exists() or view.is_symlink():
        if view.is_symlink() or view.is_file():
            view.unlink()
        else:
            shutil.rmtree(view)
    view.mkdir(parents=True)
    asset_rel = None
    if asset_root != game:
        try:
            asset_rel = asset_root.relative_to(game)
        except ValueError:
            asset_rel = None
    skip_top = asset_rel.parts[0] if asset_rel and asset_rel.parts else None
    for entry in game.iterdir():
        if entry.name.lower() in {"eboot.bin", "sce_sys", "sce_module"}:
            continue
        if skip_top and entry.name == skip_top:
            continue
        if entry.is_dir() or entry.is_file():
            _link_entry(entry, view / entry.name)
    if asset_root != game:
        for entry in asset_root.iterdir():
            if entry.name.lower() in {"eboot.bin", "sce_sys", "sce_module"}:
                continue
            _link_entry(entry, view / entry.name)
        print(f"/app0 resource tree flattened from {asset_root}", flush=True)
    for layer in filter(None, (game, update)):
        for name in ("eboot.bin", "sce_sys", "sce_module"):
            entry = layer / name
            if name == "eboot.bin" and entry.is_file():
                _link_file(entry, view / name)
            elif entry.is_dir():
                for item in entry.rglob("*"):
                    if item.is_file():
                        _link_file(item, view / name / item.relative_to(entry))
    return view


def load_patch_module(source: Path):
    path = source / "tools/probe_tlou.py"
    if not path.is_file():
        raise PortError(f"Missing game-specific patch definitions: {path}")
    spec = importlib.util.spec_from_file_location("tlou_desktop_patches", path)
    if spec is None or spec.loader is None:
        raise PortError(f"Cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prepare(source: Path, game: Path, update: Path | None, out: Path,
            *, flashlight: bool = False) -> dict:
    # Never create or delete files inside the player's game dump.
    if out == game or out.is_relative_to(game) or (update and (out == update or out.is_relative_to(update))):
        raise PortError("--out must be outside the game and update folders")
    base = require_game(game)
    validate_game_assets(game)
    new = require_game(update, is_update=True) if update else None
    # Do not claim that this layout supports update-provided game resources.
    if update and (update / "dvdroot_ps4").exists():
        raise PortError("Update has dvdroot_ps4 resources; native app0 overlay not ported yet")
    out.mkdir(parents=True, exist_ok=True)
    game_view = build_view(game, update, out)
    scripts = source / "gow3_pc/scripts"
    if not scripts.is_dir():
        raise PortError(f"Missing TLOU preparation scripts: {scripts}")
    for name in ("prepare.py", "link_modules.py", "content_profile.py"):
        command = [sys.executable, str(scripts / name), str(game_view), "--out", str(out)]
        subprocess.run(command, check=True)
    if not (out / "boot-linked.bin").is_file() or not (out / "content.bin").is_file():
        raise PortError("Preparation returned without the runtime image and content profile")
    from_module = load_patch_module(source)
    patches = dict(from_module.FIXES)
    patches["job-sleep"] = from_module.JOB_SLEEP
    # The base executable has different function locations from desktop 01.11.
    # Load only this Android module; never substitute offsets from another game.
    profile_path = source / "android/tlou_patches.py"
    if profile_path.is_file():
        profile_spec = importlib.util.spec_from_file_location("tlou_android_profiles", profile_path)
        profile_module = importlib.util.module_from_spec(profile_spec)
        profile_spec.loader.exec_module(profile_module)
        patches = profile_module.select(game_view / "eboot.bin", patches)
    if flashlight:
        patches["flashlight"] = from_module.CHEATS["flashlight"]
    patch_file = out / "patches.bin"
    patch_file.unlink(missing_ok=True)
    patched = from_module.image_patches(out / "eboot.elf", patch_file, patches)
    return {
        "title_id": base.get("TITLE_ID", TITLE_ID),
        "base_version": base.get("APP_VER"),
        "update_version": new.get("APP_VER") if new else None,
        "prepared_view": str(game_view),
        "boot": str(out / "boot-linked.bin"),
        "content": str(out / "content.bin"),
        "patches": str(patched) if patched else None,
        "version_specific_patches_verified": bool(patched),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1],
                        help="Checkout of tlou-remastered-android with gow3_pc/scripts")
    parser.add_argument("--game", type=Path, required=True, help="Owned CUSA00552 dump")
    parser.add_argument("--update", type=Path, help="Optional CUSA00552 update folder")
    parser.add_argument("--out", type=Path, required=True, help="App-private preparation directory")
    parser.add_argument("--flashlight", action="store_true", help="Optional verified 1.11 cheat")
    args = parser.parse_args(argv)
    try:
        result = prepare(args.source.resolve(), args.game.resolve(),
                         args.update.resolve() if args.update else None,
                         args.out.resolve(), flashlight=args.flashlight)
    except (PortError, OSError, subprocess.CalledProcessError) as exc:
        parser.exit(1, f"TLOU Android prepare: {exc}\n")
    for key, value in result.items():
        print(f"{key}={value}")
    print("Prepared only. ARM64 FEX loader and TLOU-specific Vulkan integration are not yet complete.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
