/**
 * @file site.ts
 * @description Central configuration file for the website's metadata, links, and constant values.
 * @module lib/site
 */

/**
 * Global site configuration object.
 * Contains metadata, external links, and author information used throughout the application.
 */
export const siteConfig = {
  name: "mpv NAS Player",
  version: "v1.3.10",
  description: "NAS-first Android video player for SMB, FTP and WebDAV, powered by libmpv.",
  url: "https://mpvex.vercel.app",
  ogImage: "https://mpvex.vercel.app/og.jpg",
  icons: {
    icon: "/icon.svg",
    apple: "/apple-icon.png",
  },
  links: {
    github: "https://github.com/XIONGPEILIN/mpvExtended-android",
    releases: "https://github.com/XIONGPEILIN/mpvExtended-android/releases",
    latestRelease: "https://github.com/XIONGPEILIN/mpvExtended-android/releases/latest",
    izzyOnAndroid: "https://apt.izzysoft.de/packages/app.marlboroadvance.mpvex",
    contributors: "https://github.com/XIONGPEILIN/mpvExtended-android/graphs/contributors",
  },
  author: {
    name: "XIONGPEILIN",
    url: "https://github.com/XIONGPEILIN",
  },
} as const;

export type SiteConfig = typeof siteConfig;
