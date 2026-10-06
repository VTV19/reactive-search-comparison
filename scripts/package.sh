#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

command -v zip >/dev/null || { echo "Для упаковки требуется стандартная утилита zip." >&2; exit 1; }
for module in search-core search-mvc search-webflux metadata-service; do
  [ -s "$module/target/$module.jar" ] || {
    echo "Отсутствует $module/target/$module.jar. Выполните bash scripts/verify.sh." >&2
    exit 1
  }
done

# Явный состав исключает .git, локальные настройки IDE, журналы и старые сборки.
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
package="$temporary/java-search-release"
mkdir -p "$package" dist
for file in pom.xml README.md compose.yaml compose.test.yaml compose.load.yaml start.sh stop.sh .gitignore .gitattributes .dockerignore; do
  cp "$file" "$package/"
done
for directory in docs data ui scripts tests; do
  cp -R "$directory" "$package/"
done
for module in search-core search-mvc search-webflux metadata-service; do
  mkdir -p "$package/$module/target"
  cp "$module/pom.xml" "$package/$module/"
  cp -R "$module/src" "$package/$module/"
  cp "$module/target/$module.jar" "$package/$module/target/"
done
find "$package" -type d \( -name __pycache__ -o -name .pytest_cache \) -prune -exec rm -rf {} +
find "$package" -type f \( -name '*.pyc' -o -name .DS_Store -o -name '.env*' -o -name '*.log' \) -delete
destination="$PWD/dist/java-search-3.1.0.zip"
(cd "$temporary" && zip -qr package.zip java-search-release)
mv "$temporary/package.zip" "$destination"
echo "Дистрибутив: $destination"
