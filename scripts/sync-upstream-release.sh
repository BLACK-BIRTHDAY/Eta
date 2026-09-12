#!/usr/bin/env bash
# 一键同步上游 Release 脚本 (Mangi-11/Eta -> 本地)
set -e

UPSTREAM_REPO="Mangi-11/Eta"
UPSTREAM_URL="https://github.com/${UPSTREAM_REPO}.git"

# 确保 upstream remote 存在
if ! git remote get-url upstream >/dev/null 2>&1; then
  echo "==> 添加 upstream 远程仓库: $UPSTREAM_URL"
  git remote add upstream "$UPSTREAM_URL"
fi

TARGET_TAG="$1"

if [ -z "$TARGET_TAG" ]; then
  echo "==> 未指定 Tag，正在查询上游最新 Release..."
  if command -v gh >/dev/null 2>&1; then
    TARGET_TAG=$(gh release view --repo "$UPSTREAM_REPO" --json tagName -q .tagName 2>/dev/null || true)
  fi

  if [ -z "$TARGET_TAG" ]; then
    echo "==> 通过 git 探测最新 tags..."
    git fetch upstream --tags >/dev/null 2>&1
    TARGET_TAG=$(git tag -l --sort=-v:refname "v*" | head -n 1)
  fi
fi

if [ -z "$TARGET_TAG" ]; then
  echo "❌ 未能获取到上游 Release Tag，请手动指定：./scripts/sync-upstream-release.sh <tag-name>"
  exit 1
fi

echo "==> 目标 Release Tag: $TARGET_TAG"
echo "==> 拉取上游 Tag 引用..."
git fetch upstream "+refs/tags/$TARGET_TAG:refs/remotes/upstream/tags/$TARGET_TAG" --no-tags

TAG_COMMIT=$(git rev-parse "refs/remotes/upstream/tags/$TARGET_TAG")
echo "==> Tag Commit: $TAG_COMMIT"

if git merge-base --is-ancestor "$TAG_COMMIT" HEAD; then
  echo "✅ 当前分支已包含 $TARGET_TAG 的全部提交，无需合并！"
  exit 0
fi

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
echo "==> 正在将 $TARGET_TAG 合并入当前分支 ($CURRENT_BRANCH)..."

git merge "$TAG_COMMIT" -m "chore: merge upstream release $TARGET_TAG into $CURRENT_BRANCH"

echo "🎉 成功合并上游 Release: $TARGET_TAG !"
