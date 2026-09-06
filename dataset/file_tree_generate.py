#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
from pathlib import Path


def build_tree(root: Path, output_file: Path) -> list[str]:
    """
    递归遍历 root，并返回文件树文本。
    目录优先、文件随后，名称按不区分大小写排序。
    """
    lines = [f"{root.name}/"]

    visited_dirs = set()

    try:
        visited_dirs.add(root.resolve())
    except OSError:
        pass

    def walk(directory: Path, prefix: str = ""):
        try:
            entries = list(directory.iterdir())
        except PermissionError:
            lines.append(prefix + "└── [无权限访问]")
            return
        except OSError as e:
            lines.append(prefix + f"└── [读取失败: {e}]")
            return

        # 不把本次生成的 TXT 文件加入文件树
        filtered = []

        for entry in entries:
            try:
                if entry.resolve() == output_file.resolve():
                    continue
            except OSError:
                pass

            filtered.append(entry)

        # 目录在前，文件在后，名称排序
        entries = sorted(
            filtered,
            key=lambda p: (
                not p.is_dir(),
                p.name.lower()
            )
        )

        for index, entry in enumerate(entries):
            is_last = index == len(entries) - 1

            connector = "└── " if is_last else "├── "
            child_prefix = prefix + ("    " if is_last else "│   ")

            try:
                is_symlink = entry.is_symlink()
                is_dir = entry.is_dir()
            except OSError:
                is_symlink = False
                is_dir = False

            if is_dir:
                if is_symlink:
                    lines.append(
                        prefix + connector + entry.name + "/ [符号链接]"
                    )
                    continue

                lines.append(
                    prefix + connector + entry.name + "/"
                )

                try:
                    resolved = entry.resolve()

                    if resolved in visited_dirs:
                        lines.append(
                            child_prefix
                            + "└── [检测到循环路径，停止遍历]"
                        )
                        continue

                    visited_dirs.add(resolved)

                except OSError:
                    pass

                walk(entry, child_prefix)

            else:
                suffix = " [符号链接]" if is_symlink else ""

                lines.append(
                    prefix
                    + connector
                    + entry.name
                    + suffix
                )

    walk(root)

    return lines


def main():
    # 脚本所在目录
    script_dir = Path(__file__).resolve().parent

    parser = argparse.ArgumentParser(
        description="递归遍历指定目录，并以文件树形式输出到 TXT。"
    )

    parser.add_argument(
        "directory",
        nargs="?",
        default=str(script_dir),
        help="要遍历的目录；未指定时默认为脚本所在目录"
    )

    parser.add_argument(
        "-o",
        "--output",
        default=None,
        help="输出 TXT 文件路径；默认 generated_file_tree.txt（不覆盖人工标注 file_tree.txt）"
    )

    args = parser.parse_args()

    # 要遍历的目录
    root = Path(args.directory).expanduser().resolve()

    if not root.exists():
        print(f"错误：路径不存在：{root}")
        return

    if not root.is_dir():
        print(f"错误：指定路径不是目录：{root}")
        return

    # 默认输出到脚本所在目录
    if args.output is None:
        output_file = script_dir / "generated_file_tree.txt"
    else:
        output_file = Path(args.output).expanduser()

        # 如果只指定文件名，也放到脚本所在目录
        if not output_file.is_absolute():
            output_file = script_dir / output_file

    output_file = output_file.resolve()

    if output_file == (script_dir / "file_tree.txt").resolve():
        parser.error("file_tree.txt 保存人工回归标注，不能用生成的文件树覆盖；请改用其他输出文件。")

    tree_lines = build_tree(
        root,
        output_file
    )

    output_file.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    output_file.write_text(
        "\n".join(tree_lines) + "\n",
        encoding="utf-8"
    )

    print(f"遍历目录：{root}")
    print(f"输出文件：{output_file}")
    print(f"共输出 {len(tree_lines)} 行。")


if __name__ == "__main__":
    main()
