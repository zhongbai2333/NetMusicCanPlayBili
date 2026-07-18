#!/usr/bin/env node

/**
 * Generate one /setblock command per chest for TeaCon player dolls.
 *
 * By default this reads the deduplicated 211-member list (including builders)
 * and places eight separate chests in a line from ~1 ~ ~ through ~15 ~ ~.
 *
 * Usage:
 *   node tools/generate_teacon_doll_chests.js
 *   node tools/generate_teacon_doll_chests.js <input.csv> <output-directory>
 */

const fs = require("node:fs/promises");
const path = require("node:path");

const DEFAULT_INPUT = path.join(
  __dirname,
  "output",
  "teacon-2026-unique-members-with-builders.csv",
);
const DEFAULT_OUTPUT_DIRECTORY = path.join(__dirname, "output", "doll-chests");
const CHEST_SIZE = 27;

function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = "";
  let quoted = false;

  for (let index = text.charCodeAt(0) === 0xfeff ? 1 : 0; index < text.length; index += 1) {
    const character = text[index];

    if (quoted) {
      if (character === '"' && text[index + 1] === '"') {
        cell += '"';
        index += 1;
      } else if (character === '"') {
        quoted = false;
      } else {
        cell += character;
      }
    } else if (character === '"') {
      quoted = true;
    } else if (character === ",") {
      row.push(cell);
      cell = "";
    } else if (character === "\n") {
      row.push(cell.replace(/\r$/, ""));
      rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += character;
    }
  }

  if (cell.length > 0 || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }

  const [headers, ...dataRows] = rows;
  return dataRows
    .filter((values) => values.some((value) => value !== ""))
    .map((values) => Object.fromEntries(headers.map((header, index) => [header, values[index] || ""])));
}

function escapeSnbtString(value) {
  return value.replaceAll("\\", "\\\\").replaceAll('"', '\\"');
}

function chestCoordinate(index) {
  return `~${index * 2 + 1} ~ ~`;
}

function dollItem(playerName, slot) {
  const profile = escapeSnbtString(playerName);
  return `{Slot:${slot}b,id:"displaydoll:player_doll",count:1,components:{"minecraft:profile":"${profile}"}}`;
}

function chestCommand(players, chestIndex) {
  const items = players.map((player, slot) => dollItem(player.minecraft_name, slot)).join(",");
  return `/setblock ${chestCoordinate(chestIndex)} minecraft:chest[facing=south]{CustomName:'{"text":"TeaCon 2026 · ${chestIndex + 1}"}',Items:[${items}]} replace`;
}

function buildManifest(chests) {
  const lines = [
    "TeaCon 2026 玩家玩偶分箱清单（包含 builder）",
    `总人数：${chests.reduce((total, chest) => total + chest.length, 0)}`,
    `箱子数：${chests.length}`,
    "",
  ];

  chests.forEach((players, chestIndex) => {
    lines.push(`箱子 ${chestIndex + 1}（${chestCoordinate(chestIndex)}，${players.length}/27）`);
    players.forEach((player, slot) => {
      lines.push(`  槽位 ${String(slot).padStart(2, "0")}: ${player.minecraft_name}${player.is_builder === "true" ? " [builder]" : ""}`);
    });
    lines.push("");
  });

  return lines.join("\r\n");
}

async function main() {
  const inputPath = path.resolve(process.argv[2] || DEFAULT_INPUT);
  const outputDirectory = path.resolve(process.argv[3] || DEFAULT_OUTPUT_DIRECTORY);
  const players = parseCsv(await fs.readFile(inputPath, "utf8"));

  if (players.length === 0) {
    throw new Error("输入 CSV 中没有玩家");
  }

  const invalidPlayers = players.filter(
    (player) => !/^[A-Za-z0-9_]{1,16}$/.test(player.minecraft_name),
  );
  if (invalidPlayers.length > 0) {
    throw new Error(
      `存在 ${invalidPlayers.length} 个无效 Minecraft 名称：${invalidPlayers
        .map((player) => player.minecraft_name || "<空>")
        .join(", ")}`,
    );
  }

  const duplicateNames = players
    .map((player) => player.minecraft_name.toLowerCase())
    .filter((name, index, names) => names.indexOf(name) !== index);
  if (duplicateNames.length > 0) {
    throw new Error(`存在重复 Minecraft 名称：${[...new Set(duplicateNames)].join(", ")}`);
  }

  const chests = [];
  for (let index = 0; index < players.length; index += CHEST_SIZE) {
    chests.push(players.slice(index, index + CHEST_SIZE));
  }

  const commands = chests.map(chestCommand);
  const functionCommands = commands.map((command) => command.slice(1));
  await fs.mkdir(outputDirectory, { recursive: true });
  await Promise.all([
    fs.writeFile(
      path.join(outputDirectory, "teacon-2026-doll-chests-with-builders.mcfunction"),
      `${functionCommands.join("\n")}\n`,
      "utf8",
    ),
    fs.writeFile(
      path.join(outputDirectory, "teacon-2026-doll-chests-with-builders.txt"),
      `${commands.join("\r\n")}\r\n`,
      "utf8",
    ),
    fs.writeFile(
      path.join(outputDirectory, "teacon-2026-doll-chests-manifest.txt"),
      buildManifest(chests),
      "utf8",
    ),
  ]);

  console.log(`输入玩家：${players.length}`);
  console.log(`生成箱子：${chests.length}`);
  console.log(`每箱数量：${chests.map((chest) => chest.length).join(", ")}`);
  console.log(`输出目录：${outputDirectory}`);
}

main().catch((error) => {
  console.error(`生成失败：${error.message}`);
  process.exitCode = 1;
});