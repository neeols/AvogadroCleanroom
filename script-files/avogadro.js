/* Small enhancements to standard Javadoc pages. */
document.addEventListener("DOMContentLoaded", () => {
  const guide = document.querySelector(".av-guide");
  if (!guide) return;
  const holder = guide.querySelector(".av-language-controls");
  const label = document.createElement("label");
  label.textContent = "Example language ";
  const selector = document.createElement("select");
  selector.setAttribute("aria-label", "Example language");
  ["java", "groovy", "json"].forEach((value) => {
    const option = document.createElement("option");
    option.value = value;
    option.textContent =
      value === "json" ? "JSON" : value === "java" ? "Java" : "Groovy";
    selector.append(option);
  });
  label.append(selector);
  holder.replaceChildren(label);
  guide.querySelectorAll("img").forEach((img) => {
    img.loading = "lazy";
  });
  guide.querySelectorAll("a.av-video").forEach((link) => {
    const video = document.createElement("video");
    video.controls = true;
    video.preload = "none";
    video.src = link.href;
    video.poster = link.dataset.poster;
    link.replaceWith(video);
  });
  const languages = ["java", "groovy", "json"];
  const choose = (value) => {
    if (!languages.includes(value)) value = "java";
    selector.value = value;
    guide.querySelectorAll("[data-language]").forEach((block) => {
      block.hidden = !block.dataset.language.split(/\s+/).includes(value);
    });
    try {
      localStorage.setItem("avogadro-example-language", value);
    } catch {
      /* Storage is optional. */
    }
  };
  let preferred = "java";
  try {
    preferred = localStorage.getItem("avogadro-example-language") || preferred;
  } catch {
    /* File viewers may deny storage. */
  }
  choose(
    new URLSearchParams(window.location.search).get("language") || preferred,
  );
  selector.addEventListener("change", () => choose(selector.value));
  guide.querySelectorAll("[data-choose-language]").forEach((button) => {
    button.addEventListener("click", () =>
      choose(button.dataset.chooseLanguage),
    );
  });
  const headings = [...guide.querySelectorAll("h2[id]")];
  if (headings.length > 1) {
    const nav = document.createElement("nav");
    nav.className = "av-toc";
    nav.setAttribute("aria-label", "On this page");
    const label = document.createElement("strong");
    label.textContent = "On this page";
    nav.append(label);
    const list = document.createElement("ul");
    headings.forEach((heading) => {
      const item = document.createElement("li");
      const link = document.createElement("a");
      link.href = "#" + heading.id;
      link.textContent = heading.textContent;
      item.append(link);
      list.append(item);
    });
    nav.append(list);
    (guide.querySelector(".av-lead") || guide.querySelector("h1")).after(nav);
  }
  guide.querySelectorAll("pre").forEach((pre) => {
    const wrap = document.createElement("div");
    wrap.className = "av-code-wrap";
    pre.before(wrap);
    const button = document.createElement("button");
    button.className = "av-copy";
    button.type = "button";
    button.textContent = "Copy";
    button.setAttribute("aria-label", "Copy this code example");
    button.setAttribute("aria-live", "polite");
    wrap.append(button, pre);
    button.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(pre.textContent);
        button.textContent = "Copied";
      } catch {
        const range = document.createRange();
        range.selectNodeContents(pre);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        button.textContent = "Selected";
      }
      window.setTimeout(() => {
        button.textContent = "Copy";
      }, 2000);
    });
  });
});
