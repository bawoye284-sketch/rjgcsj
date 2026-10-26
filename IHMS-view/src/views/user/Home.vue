<template>
    <div class="home-dashboard">
        <section class="welcome-panel">
            <div><span class="eyebrow">PERSONAL HEALTH · 资讯中心</span><h1>照顾好每一天的健康</h1><p>阅读可靠的健康知识，记录身体变化，发现异常时及时获得提醒。</p></div>
            <div class="welcome-actions"><el-button type="primary" icon="el-icon-data-analysis" @click="$router.push('/health-assessment')">开始健康评估</el-button><el-button plain icon="el-icon-warning" @click="$router.push('/sos-help')">紧急求助</el-button></div>
        </section>
        <section v-if="newsTopList.length" class="featured-layout">
            <div class="featured-banner"><Banner :data="newsTopList" @on-click="onBannerClick" /></div>
            <div class="featured-grid"><article v-for="(news, index) in newsTopList.slice(0, 4)" :key="index" class="featured-item" @click="newsItemClick(news)">
                <img :src="news.cover" :alt="news.name"><div class="featured-copy"><span class="news-tags">{{ news.tagName || '健康资讯' }}</span><h2>{{ news.name }}</h2><time>{{ parseTime(news.createTime) }}</time></div>
            </article></div>
        </section>
        <section class="news-section">
            <div class="section-heading"><div><span class="eyebrow">EXPLORE</span><h2>健康资讯</h2></div><span class="section-note">按主题筛选你感兴趣的内容</span></div>
            <div class="tag-panel"><TagLine :dataList="tagsList" @on-click="tagOnClick" /></div>
            <el-empty v-if="newsList.length === 0" description="暂无资讯"></el-empty>
            <div v-else class="news-grid"><article class="news-card" v-for="(news, index) in newsList" :key="index" @click="newsItemClick(news)">
                <img :src="news.cover" :alt="news.name"><div class="news-card-body"><span class="news-tags">{{ news.tagName || '健康资讯' }}</span><h3>{{ news.name }}</h3><time>{{ parseTime(news.createTime) }}</time></div>
            </article></div>
        </section>
    </div>
</template>
<script>
import TagLine from "@/components/TagLine"
import Banner from "@/components/Banner"
import { timeAgo } from "@/utils/data"
export default {
    components: { TagLine, Banner },
    data() {
        return {
            tagsList: [], // 标签列表
            newsList: [], // 健康资讯列表
            newsTopList: [], // 推荐的健康资讯数据列表
            newQueryDto: { tagId: null },
        }
    },
    created() {
        this.loadAllTags();
        this.loadAllNews();
        this.loadAllTopNews();
    },
    methods: {
        // 轮播图点击事件回传
        onBannerClick(banner) {
            sessionStorage.setItem('newsInfo', JSON.stringify(banner));
            this.$router.push('/news-detail');
        },
        // 健康资讯列表的项点击事件
        newsItemClick(news) {
            sessionStorage.setItem('newsInfo', JSON.stringify(news));
            this.$router.push('/news-detail');
        },
        // 转换时间
        parseTime(time) {
            return timeAgo(time);
        },
        tagOnClick(tags) {
            this.newQueryDto.tagId = tags.id;
            this.loadAllNews();
        },
        // 查询全部的标签记录
        loadAllTags() {
            this.$axios.post('/tags/query', {}).then(response => {
                const { data } = response;
                if (data.code === 200) {
                    this.tagsList = data.data;
                    this.tagsList.unshift({ name: '全部', id: null });
                }
            })
        },
        // 查标签下的资讯信息
        loadAllTopNews() {
            const newQueryDto = { isTop: true };
            this.$axios.post('/news/query', newQueryDto).then(response => {
                const { data } = response;
                if (data.code === 200) {
                    this.newsTopList = data.data;
                }
            })
        },
        // 查标签下的资讯信息
        loadAllNews() {
            this.$axios.post('/news/query', this.newQueryDto).then(response => {
                const { data } = response;
                if (data.code === 200) {
                    this.newsList = data.data;
                }
            })
        }
    }
};
</script>

<style scoped lang="scss">
.home-dashboard { max-width: 1280px; margin: 0 auto; padding: 8px 4px 34px; color: #243b53; }
.welcome-panel { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 30px 34px; margin-bottom: 20px; border-radius: 18px; background: linear-gradient(120deg, #eaf6ff 0%, #f5fbf8 100%); box-shadow: 0 8px 24px rgba(45, 88, 110, .08); }
.eyebrow { color: #46849b; font-size: 11px; font-weight: 700; letter-spacing: 1.4px; }
.welcome-panel h1 { margin: 8px 0 10px; color: #234e60; font-size: 30px; line-height: 1.2; }
.welcome-panel p { margin: 0; color: #617887; font-size: 14px; }
.welcome-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.featured-layout { display: grid; grid-template-columns: minmax(280px, 1fr) minmax(0, 1.45fr); gap: 18px; margin-bottom: 24px; }
.featured-banner { min-height: 250px; padding: 10px; border: 1px solid #e5edf2; border-radius: 14px; background: #fff; overflow: hidden; }
.featured-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.featured-item { display: flex; gap: 12px; min-width: 0; padding: 10px; border: 1px solid #e7edf2; border-radius: 12px; background: #fff; cursor: pointer; transition: transform .18s, box-shadow .18s; }
.featured-item:hover, .news-card:hover { transform: translateY(-2px); box-shadow: 0 8px 18px rgba(45, 88, 110, .12); }
.featured-item img { width: 112px; height: 92px; flex: 0 0 112px; object-fit: cover; border-radius: 9px; }
.featured-copy { min-width: 0; }
.featured-copy h2 { margin: 9px 0 7px; color: #294b5a; font-size: 16px; line-height: 1.35; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.featured-copy time, .news-card time { color: #8b9aa5; font-size: 12px; }
.news-section { padding: 22px; border: 1px solid #e8eef2; border-radius: 16px; background: #fff; }
.section-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 8px; }
.section-heading h2 { margin: 5px 0 0; color: #294b5a; font-size: 24px; }
.section-note { color: #8b9aa5; font-size: 13px; }
.tag-panel { padding: 9px 0 15px; border-bottom: 1px solid #edf2f5; }
.news-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 18px; padding-top: 18px; }
.news-card { min-width: 0; overflow: hidden; border: 1px solid #e7edf2; border-radius: 12px; background: #fff; cursor: pointer; transition: transform .18s, box-shadow .18s; }
.news-card img { display: block; width: 100%; height: 138px; object-fit: cover; }
.news-card-body { padding: 12px 13px 14px; }
.news-card h3 { margin: 9px 0 8px; color: #294b5a; font-size: 15px; line-height: 1.45; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.news-tags {
    display: inline-block;
    padding: 3px 7px;
    background-color: #e5f2f7;
    color: #3e7183;
    border-radius: 999px;
    font-size: 11px;
}
@media (max-width: 1000px) { .news-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 760px) { .welcome-panel, .section-heading { align-items: flex-start; flex-direction: column; } .featured-layout { grid-template-columns: 1fr; } .news-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 480px) { .home-dashboard { padding: 4px 0 22px; } .welcome-panel, .news-section { padding: 18px 14px; border-radius: 12px; } .featured-grid, .news-grid { grid-template-columns: 1fr; } .featured-item img { width: 96px; flex-basis: 96px; } }
</style>
