<script setup>
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import 'highlight.js/styles/github.css'
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'

const props = defineProps({ content: { type: String, default: '' } })

for (const [name, language] of Object.entries({ bash, javascript, json, python, sql })) {
  hljs.registerLanguage(name, language)
}

const markdown = new MarkdownIt({
  html: false,
  breaks: true,
  highlight(source, language) {
    return language && hljs.getLanguage(language) ? hljs.highlight(source, { language }).value : ''
  },
}).disable('image')

const rendered = computed(() => markdown.render(props.content))
</script>

<template>
  <div class="markdown-body" v-html="rendered"></div>
</template>
