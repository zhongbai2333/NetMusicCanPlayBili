#!/usr/bin/env node

/**
 * Export TeaCon 2026 projects and their members from the official website.
 *
 * The website embeds all teams in a Next.js flight-data script. Reading that
 * data is both more complete (it includes visually collapsed members) and
 * gentler on the site than visiting every client-side pagination page.
 *
 * Usage:
 *   node tools/teacon_2026_developers.js [output-directory]
 *
 * Outputs:
 *   - teacon-2026-project-developers.csv: developers shown as avatars
 *   - teacon-2026-unique-developers-without-builders.csv: developers only
 *   - teacon-2026-unique-members-with-builders.csv: developers and builders
 *   - teacon-2026-all-team-members.csv: developers plus builder members
 */

const fs = require("node:fs/promises");
const path = require("node:path");

const SOURCE_URL = "https://www.teacon.cn/2026";
const DEFAULT_OUTPUT_DIRECTORY = path.join(__dirname, "output");
const TEAMS_MARKER = '"teams":';

function decodeHtmlEntities(text) {
  return text
    .replaceAll("&quot;", '"')
    .replaceAll("&#x27;", "'")
    .replaceAll("&#39;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&amp;", "&");
}

function findJsonArrayEnd(text, start) {
  let depth = 0;
  let escaped = false;
  let inString = false;

  for (let index = start; index < text.length; index += 1) {
    const character = text[index];

    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      continue;
    }

    if (character === '"') {
      inString = true;
    } else if (character === "[") {
      depth += 1;
    } else if (character === "]") {
      depth -= 1;
      if (depth === 0) {
        return index + 1;
      }
    }
  }

  throw new Error("官网数据中的 teams 数组没有正确结束");
}

function extractTeams(html) {
  const scriptPattern = /<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi;

  for (const match of html.matchAll(scriptPattern)) {
    const script = decodeHtmlEntities(match[1]).trim();
    const pushPrefix = "self.__next_f.push(";
    if (!script.startsWith(pushPrefix) || !script.endsWith(")")) {
      continue;
    }

    let flightChunk;
    try {
      const pushArguments = JSON.parse(
        script.slice(pushPrefix.length, -1),
      );
      flightChunk = pushArguments[1];
    } catch {
      continue;
    }

    if (typeof flightChunk !== "string") {
      continue;
    }

    const markerIndex = flightChunk.indexOf(TEAMS_MARKER);
    if (markerIndex < 0) {
      continue;
    }

    const arrayStart = markerIndex + TEAMS_MARKER.length;
    if (flightChunk[arrayStart] !== "[") {
      continue;
    }

    const arrayEnd = findJsonArrayEnd(flightChunk, arrayStart);
    const teams = JSON.parse(flightChunk.slice(arrayStart, arrayEnd));
    if (Array.isArray(teams) && teams.length > 0) {
      return teams;
    }
  }

  throw new Error("未在官网 HTML 中找到 TeaCon 2026 teams 数据");
}

function csvCell(value) {
  const text = value === null || value === undefined ? "" : String(value);
  return `"${text.replaceAll('"', '""')}"`;
}

function toCsv(headers, rows) {
  const lines = [headers.map(csvCell).join(",")];
  for (const row of rows) {
    lines.push(headers.map((header) => csvCell(row[header])).join(","));
  }
  return `\uFEFF${lines.join("\r\n")}\r\n`;
}

function memberName(member) {
  return member.minecraft_name || member.username || member.id;
}

function buildProjectMemberRows(teams) {
  return teams.flatMap((team) =>
    (team.members || []).map((member) => ({
      project_id: team.id,
      project_name: team.work_name || team.name,
      team_name: team.name,
      project_type: team.type === 0 ? "参赛模组" : "参展/往届模组",
      repository: team.repo || "",
      developer_id: member.id,
      developer_name: memberName(member),
      minecraft_name: member.minecraft_name || "",
      username: member.username || "",
      is_builder: Boolean(member.builder),
      is_staff: Boolean(member.staff),
      avatar_url: `https://biluochun.teacon.cn/api/v1/user/avatar?user=${encodeURIComponent(member.id)}`,
    })),
  );
}

function buildUniqueDeveloperRows(projectMemberRows) {
  const developers = new Map();

  for (const row of projectMemberRows) {
    let developer = developers.get(row.developer_id);
    if (!developer) {
      developer = {
        developer_id: row.developer_id,
        developer_name: row.developer_name,
        minecraft_name: row.minecraft_name,
        username: row.username,
        is_builder: row.is_builder,
        is_staff: row.is_staff,
        project_count: 0,
        project_ids: [],
        project_names: [],
        avatar_url: row.avatar_url,
      };
      developers.set(row.developer_id, developer);
    }

    developer.is_builder ||= row.is_builder;
    developer.is_staff ||= row.is_staff;
    developer.project_ids.push(row.project_id);
    developer.project_names.push(row.project_name);
    developer.project_count += 1;
  }

  return [...developers.values()]
    .map((developer) => ({
      ...developer,
      project_ids: developer.project_ids.join(" | "),
      project_names: developer.project_names.join(" | "),
    }))
    .sort((left, right) =>
      left.developer_name.localeCompare(right.developer_name, "zh-CN"),
    );
}

async function fetchHomepage() {
  const response = await fetch(SOURCE_URL, {
    headers: {
      "User-Agent": "TeaCon-2026-developer-exporter/1.0 (one-time public data export)",
      Accept: "text/html,application/xhtml+xml",
    },
    signal: AbortSignal.timeout(30_000),
  });

  if (!response.ok) {
    throw new Error(`请求官网失败：HTTP ${response.status} ${response.statusText}`);
  }
  return response.text();
}

async function main() {
  const outputDirectory = path.resolve(process.argv[2] || DEFAULT_OUTPUT_DIRECTORY);
  const html = await fetchHomepage();
  const teams = extractTeams(html);
  const allTeamMemberRows = buildProjectMemberRows(teams);
  // This matches the website's avatar list: its UI filters out builder members
  // and represents them only with a "+N" badge.
  const projectDeveloperRows = allTeamMemberRows.filter((row) => !row.is_builder);
  const uniqueDevelopersWithoutBuilders = buildUniqueDeveloperRows(projectDeveloperRows);
  const uniqueMembersWithBuilders = buildUniqueDeveloperRows(allTeamMemberRows);

  const projectMemberHeaders = [
    "project_id", "project_name", "team_name", "project_type", "repository",
    "developer_id", "developer_name", "minecraft_name", "username",
    "is_builder", "is_staff", "avatar_url",
  ];
  const uniqueDeveloperHeaders = [
    "developer_id", "developer_name", "minecraft_name", "username",
    "is_builder", "is_staff", "project_count", "project_ids",
    "project_names", "avatar_url",
  ];

  await fs.mkdir(outputDirectory, { recursive: true });
  await fs.rm(
    path.join(outputDirectory, "teacon-2026-unique-developers.csv"),
    { force: true },
  );
  await Promise.all([
    fs.writeFile(
      path.join(outputDirectory, "teacon-2026-project-developers.csv"),
      toCsv(projectMemberHeaders, projectDeveloperRows),
      "utf8",
    ),
    fs.writeFile(
      path.join(outputDirectory, "teacon-2026-unique-developers-without-builders.csv"),
      toCsv(uniqueDeveloperHeaders, uniqueDevelopersWithoutBuilders),
      "utf8",
    ),
    fs.writeFile(
      path.join(outputDirectory, "teacon-2026-unique-members-with-builders.csv"),
      toCsv(uniqueDeveloperHeaders, uniqueMembersWithBuilders),
      "utf8",
    ),
    fs.writeFile(
      path.join(outputDirectory, "teacon-2026-all-team-members.csv"),
      toCsv(projectMemberHeaders, allTeamMemberRows),
      "utf8",
    ),
  ]);

  const builderCount = allTeamMemberRows.filter((row) => row.is_builder).length;
  console.log(`数据源：${SOURCE_URL}`);
  console.log(`项目：${teams.length}`);
  console.log(`开发者关系：${projectDeveloperRows.length}`);
  console.log(`去重名单（不含 builder）：${uniqueDevelopersWithoutBuilders.length}`);
  console.log(`去重名单（包含 builder）：${uniqueMembersWithBuilders.length}`);
  console.log(`全体成员关系：${allTeamMemberRows.length}（其中 builder ${builderCount} 条）`);
  console.log(`输出目录：${outputDirectory}`);
}

main().catch((error) => {
  console.error(`导出失败：${error.message}`);
  process.exitCode = 1;
});