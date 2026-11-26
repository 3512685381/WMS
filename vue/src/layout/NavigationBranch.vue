<script setup>
import { ArrowDown, Document } from '@element-plus/icons-vue'
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { menuIcons } from '../components/menuIcons'

const props = defineProps({
  node: { type: Object, required: true },
  nested: Boolean,
})
const emit = defineEmits(['navigate'])
const route = useRoute()
const opened = ref(false)

watch(() => route.path, (path) => {
  if (path.startsWith(props.node.path + '/')) opened.value = true
}, { immediate: true })
</script>

<template>
  <li class="nav-node">
    <button
      v-if="node.menuType === 'M'"
      type="button"
      class="nav-link nav-folder"
      :aria-expanded="opened"
      @click="opened = !opened"
    >
      <component :is="menuIcons[node.icon] || Document" class="icon" aria-hidden="true" />
      <span>{{ node.menuName }}</span>
      <ArrowDown class="icon nav-caret" :class="{ 'nav-caret-open': opened }" aria-hidden="true" />
    </button>
    <RouterLink
      v-else
      :to="node.path"
      class="nav-link"
      :class="{ 'nav-child': nested }"
      exact-active-class="nav-active"
      @click="emit('navigate')"
    >
      <component v-if="!nested || node.icon" :is="menuIcons[node.icon] || Document" class="icon" aria-hidden="true" />
      <span>{{ node.menuName }}</span>
    </RouterLink>

    <ul v-if="opened && node.children.length" class="nav-children">
      <NavigationBranch
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        nested
        @navigate="emit('navigate')"
      />
    </ul>
  </li>
</template>
